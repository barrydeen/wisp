package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.PublishResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface NotePublicationTransport {
    val results: Flow<PublishResult>
    /** Verified relay copies. The publisher additionally matches every signed field. */
    val relayCopies: Flow<Pair<NostrEvent, String>> get() = emptyFlow()
    fun targetRelays(inboxPubkeys: Collection<String>): Set<String>

    /** Returns false if the connection or send failed. Must never queue an EVENT for reconnect. */
    suspend fun send(relayUrl: String, event: NostrEvent): Boolean
}

/** Own public posts only. This deliberately excludes private rumors, DMs and scheduled posts. */
class NotePublisher(
    val accountPubkey: String?,
    private val store: NotePublicationStore,
    private val transport: NotePublicationTransport,
    scope: CoroutineScope,
    private val onStored: (NostrEvent) -> Unit,
    private val isDeleted: (NostrEvent) -> Boolean = { false },
    private val timeoutMs: Long = 10_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val verifiedRelayUrls: (NostrEvent) -> Set<String> = { emptySet() },
    private val beforeRestore: suspend () -> Unit = {}
) {
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private val accountJob = SupervisorJob(scope.coroutineContext[Job])
    private val accountScope = CoroutineScope(scope.coroutineContext + accountJob)
    val isActive: Boolean get() = !closed && accountJob.isActive
    private val operations = mutableSetOf<Job>()
    private val deliveries = mutableMapOf<String, Deferred<NotePublication>>()

    /** Stop collectors, restoration, delivery and PoW work without deleting recovery files. */
    fun close(): Job = synchronized(lifecycleLock) {
        closed = true
        operations.toList().forEach { it.cancel() }
        accountJob.cancel()
        accountJob
    }

    fun launchWork(block: suspend CoroutineScope.() -> Unit): Job = accountScope.launch(block = block)

    private fun stored(event: NostrEvent) = synchronized(lifecycleLock) {
        if (!closed) onStored(event)
    }

    private val mutex = Mutex()
    private val active = mutableSetOf<String>()
    private val _publications = MutableStateFlow<Map<String, NotePublication>>(emptyMap())
    val publications: StateFlow<Map<String, NotePublication>> = _publications
    private val _receipts = MutableStateFlow<Map<String, NotePublicationReceipt>>(emptyMap())
    val receipts: StateFlow<Map<String, NotePublicationReceipt>> = _receipts
    private val _latest = MutableStateFlow<NotePublication?>(null)
    val latest: StateFlow<NotePublication?> = _latest
    private val _actionErrors = MutableStateFlow<Set<String>>(emptySet())
    val actionErrors: StateFlow<Set<String>> = _actionErrors

    private val ready = accountScope.async(ioDispatcher) {
        beforeRestore()
        currentCoroutineContext().ensureActive()
        val receipts = store.loadReceipts().takeLast(PUBLICATION_RECEIPT_LIMIT).associateBy { it.eventId }
        val restored = store.load().filter {
            canPublish(it.event) && it.acceptedCount == 0 && it.event.id !in receipts
        }.map { saved ->
            val recovered = saved.interrupted()
            if (recovered != saved) {
                try {
                    store.save(recovered)
                    recovered
                } catch (_: Exception) {
                    recovered.copy(storageError = true)
                }
            } else recovered
        }
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            _receipts.value = receipts
            _publications.value = restored.associateBy { it.event.id }
        }
        restored.forEach { stored(it.event) }
    }

    init {
        // Subscribe before any send, including a relay that replies synchronously.
        accountScope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            transport.results.collect { result ->
                ready.await()
                mutex.withLock {
                    val current = _publications.value[result.eventId] ?: return@withLock
                    val previous = current.relays[result.relayUrl] ?: return@withLock
                    // A rejection or duplicate response must not erase confirmed acceptance.
                    if (previous.status == RelayPublicationStatus.ACCEPTED) return@withLock
                    val status = if (result.accepted) RelayPublicationStatus.ACCEPTED else RelayPublicationStatus.REJECTED
                    update(current.copy(relays = current.relays + (result.relayUrl to RelayPublication(status, result.message))))
                }
            }
        }
        accountScope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            transport.relayCopies.collect { (event, url) ->
                ready.await()
                mutex.withLock {
                    val current = _publications.value[event.id] ?: return@withLock
                    // Match the entire signed event; an unverified event with a copied ID is insufficient.
                    if (current.event != event || current.relays[url]?.status == RelayPublicationStatus.ACCEPTED) return@withLock
                    update(current.copy(relays = current.relays + (url to RelayPublication(RelayPublicationStatus.ACCEPTED))))
                }
            }
        }
    }

    fun canPublish(event: NostrEvent): Boolean =
        isActive && accountPubkey != null && event.pubkey == accountPubkey && event.sig.isNotBlank() &&
            event.kind in PUBLIC_POST_KINDS && event.created_at <= System.currentTimeMillis() / 1000 + 30 &&
            !isDeleted(event)

    fun publicationFor(event: NostrEvent): NotePublication? =
        _publications.value[event.id] ?: _receipts.value[event.id]?.withEvent(event)

    /** Return once the signed event is durable. Delivery belongs to this account's scope. */
    suspend fun submit(
        event: NostrEvent,
        inboxPubkeys: Collection<String> = event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    ): NotePublication = prepare(event, inboxPubkeys).await().first

    /** Await delivery for status consumers; editor completion should use submit instead. */
    suspend fun publish(
        event: NostrEvent,
        inboxPubkeys: Collection<String> = event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    ): NotePublication {
        // One operation owns both preparation and delivery. Account shutdown and caller
        // cancellation both stop it, even while restoration, the mutex or storage is pending.
        val operation = SupervisorJob(currentCoroutineContext()[Job])
        synchronized(lifecycleLock) {
            if (closed) operation.cancel() else operations.add(operation)
        }
        operation.invokeOnCompletion { synchronized(lifecycleLock) { operations.remove(operation) } }
        // Account teardown must also await caller-owned preparation, including blocking writes.
        accountScope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                operation.join()
            } finally {
                operation.cancel()
                withContext(NonCancellable) { operation.join() }
            }
        }
        val operationScope = CoroutineScope(accountScope.coroutineContext + operation)
        try {
            val prepared = prepare(event, inboxPubkeys, operationScope).await()
            if (prepared.third) return prepared.first
            return prepared.second.await()
        } finally {
            operation.cancel()
            withContext(NonCancellable) { operation.join() }
        }
    }

    private fun prepare(
        event: NostrEvent,
        inboxPubkeys: Collection<String>,
        operationScope: CoroutineScope = accountScope
    ) = operationScope.async(ioDispatcher) {
        ready.await()
        require(canPublish(event)) { "Only the active account's public posts can be rebroadcast" }
        val targets = transport.targetRelays(inboxPubkeys)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val previous = _publications.value[event.id]
            if (event.id in active) return@withLock Triple(requireNotNull(previous), deliveries.getValue(event.id), true)
            require(previous == null || previous.event == event) { "Rebroadcast must preserve the signed event" }
            val verified = verifiedRelayUrls(event).associateWith { RelayPublication(RelayPublicationStatus.ACCEPTED) }
            val accepted = (_receipts.value[event.id]?.relays.orEmpty() + previous?.relays.orEmpty())
                .filterValues { it.status == RelayPublicationStatus.ACCEPTED } + verified
            val pending = targets.associateWith { accepted[it] ?: RelayPublication(RelayPublicationStatus.PENDING) }
            val next = NotePublication(event, inboxPubkeys.distinct(), accepted + pending, (previous?.attempt ?: 0) + 1)
            // Cancellation cannot interrupt blocking atomic IO. If the write completed,
            // retain and expose the payload, but never hand a cancelled operation to delivery.
            store.save(next)
            setPublication(next)
            _latest.value = next
            var cached = false
            try {
                currentCoroutineContext().ensureActive()
                rememberReceipt(next)
                _actionErrors.value = _actionErrors.value - event.id
                stored(event)
                cached = true
                currentCoroutineContext().ensureActive()
                active.add(event.id)
                val delivery = operationScope.async(ioDispatcher, start = CoroutineStart.UNDISPATCHED) { deliver(next, targets) }
                deliveries[event.id] = delivery
                // UNDISPATCHED enters delivery's finally even if cancellation races handoff.
                Triple(next, delivery, false)
            } catch (e: CancellationException) {
                deliveries.remove(event.id)?.cancel()
                active.remove(event.id)
                update(next.interrupted())
                if (!cached) stored(event)
                throw e
            }
        }
    }

    private suspend fun deliver(publication: NotePublication, targets: Set<String>): NotePublication {
        val event = publication.event
        try {
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    for (url in targets) launch {
                        currentCoroutineContext().ensureActive()
                        val sent = try {
                            transport.send(url, event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (!sent) mutex.withLock {
                            val current = _publications.value.getValue(event.id)
                            if (current.relays[url]?.status == RelayPublicationStatus.PENDING) {
                                update(current.copy(relays = current.relays + (url to RelayPublication(RelayPublicationStatus.UNREACHABLE))))
                            }
                        }
                    }
                }
                publications.first { notes ->
                    notes.getValue(event.id).relays.values.none { it.status == RelayPublicationStatus.PENDING }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val current = _publications.value.getValue(event.id)
                    if (current.attempt == publication.attempt) update(current.interrupted())
                    active.remove(event.id)
                    deliveries.remove(event.id)
                }
            }
        }
        return _publications.value.getValue(event.id)
    }

    fun rebroadcast(event: NostrEvent) {
        accountScope.launch(ioDispatcher) {
            try {
                ready.await()
                val saved = _publications.value[event.id]
                publish(saved?.event ?: event, saved?.inboxPubkeys ?: event.tags.filter {
                    it.size >= 2 && it[0] == "p"
                }.map { it[1] })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutex.withLock { _actionErrors.value = _actionErrors.value + event.id }
            }
        }
    }

    /** Called with the mutex held on IO. The initial saved event survives a later write failure. */
    private fun update(publication: NotePublication) {
        val saved = try {
            store.save(publication.copy(storageError = false))
            rememberReceipt(publication)
            publication.copy(storageError = false)
        } catch (_: Exception) {
            publication.copy(storageError = true)
        }
        setPublication(saved)
        if (_latest.value?.event?.id == saved.event.id) _latest.value = saved
    }

    private fun rememberReceipt(publication: NotePublication) {
        if (publication.acceptedCount == 0) return
        _receipts.value = ((_receipts.value - publication.event.id) + (publication.event.id to publication.receipt()))
            .entries.toList().takeLast(PUBLICATION_RECEIPT_LIMIT).associate { it.toPair() }
    }

    private fun setPublication(publication: NotePublication) {
        // Bound confirmed in-memory records too, while retaining every unresolved or active post.
        _publications.value = (_publications.value + (publication.event.id to publication)).filter { (id, saved) ->
            saved.acceptedCount == 0 || saved.inFlight || id in _receipts.value || saved.storageError
        }
    }

    companion object {
        private val PUBLIC_POST_KINDS = setOf(1, 1111, 20, 21, 22, 1068, 6969)
    }
}
