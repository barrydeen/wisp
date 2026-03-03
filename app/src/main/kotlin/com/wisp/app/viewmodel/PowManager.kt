package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.PowPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class PowStatus {
    data object Idle : PowStatus()
    data class Mining(val kind: Int, val attempts: Long, val difficulty: Int) : PowStatus()
    data class Done(val message: String) : PowStatus()
    data class Failed(val message: String) : PowStatus()
}

class PowManager(
    private val powPrefs: PowPreferences,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow<PowStatus>(PowStatus.Idle)
    val status: StateFlow<PowStatus> = _status

    private var miningJob: Job? = null

    val isBusy: Boolean get() = _status.value is PowStatus.Mining

    fun submitNote(
        signer: NostrSigner,
        content: String,
        tags: List<List<String>>,
        kind: Int = 1,
        replyToPubkey: String? = null,
        onPublished: (() -> Unit)? = null
    ) {
        miningJob?.cancel()
        val difficulty = powPrefs.getNoteDifficulty()
        val createdAt = System.currentTimeMillis() / 1000

        miningJob = scope.launch {
            try {
                _status.value = PowStatus.Mining(kind, 0, difficulty)

                val result = withContext(Dispatchers.Default) {
                    Nip13.mine(
                        pubkeyHex = signer.pubkeyHex,
                        kind = kind,
                        content = content,
                        tags = tags,
                        targetDifficulty = difficulty,
                        createdAt = createdAt,
                        onProgress = { attempts ->
                            _status.value = PowStatus.Mining(kind, attempts, difficulty)
                        }
                    )
                }

                val event = signer.signEvent(
                    kind = kind,
                    content = content,
                    tags = result.tags,
                    createdAt = result.createdAt
                )

                val msg = ClientMessage.event(event)
                var sentCount = if (replyToPubkey != null) {
                    outboxRouter.publishToInbox(msg, replyToPubkey)
                } else {
                    relayPool.sendToWriteRelays(msg)
                }

                if (sentCount == 0) {
                    val reconnected = relayPool.ensureWriteRelaysConnected()
                    if (reconnected > 0) {
                        sentCount = if (replyToPubkey != null) {
                            outboxRouter.publishToInbox(msg, replyToPubkey)
                        } else {
                            relayPool.sendToWriteRelays(msg)
                        }
                    }
                }

                if (sentCount == 0) {
                    _status.value = PowStatus.Failed("No relays connected")
                    delay(3000)
                    _status.value = PowStatus.Idle
                    return@launch
                }

                relayPool.trackPublish(event.id, sentCount)
                eventRepo.addEvent(event)
                onPublished?.invoke()

                _status.value = PowStatus.Done("Published to $sentCount relay${if (sentCount != 1) "s" else ""}")
                delay(3000)
                _status.value = PowStatus.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                _status.value = PowStatus.Idle
                throw e
            } catch (e: Exception) {
                _status.value = PowStatus.Failed(e.message ?: "Mining failed")
                delay(3000)
                _status.value = PowStatus.Idle
            }
        }
    }

    fun cancel() {
        miningJob?.cancel()
        miningJob = null
        _status.value = PowStatus.Idle
    }
}
