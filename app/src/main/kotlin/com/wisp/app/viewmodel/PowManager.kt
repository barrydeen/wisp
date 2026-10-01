package com.wisp.app.viewmodel

import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.repo.NotePublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

sealed class PowStatus {
    data object Idle : PowStatus()
    data class Mining(val kind: Int, val attempts: Long, val difficulty: Int) : PowStatus()
    data class Done(val message: String) : PowStatus()
    data class Failed(val message: String) : PowStatus()
}

class PowManager(
    private val getDifficulty: () -> Int,
    private val getPublisher: () -> NotePublisher?,
    private val miningDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _status = MutableStateFlow<PowStatus>(PowStatus.Idle)
    val status: StateFlow<PowStatus> = _status

    private var miningJob: Job? = null
    private var generation = 0L

    val isBusy: Boolean get() = _status.value is PowStatus.Mining

    fun submitNote(
        signer: NostrSigner,
        content: String,
        tags: List<List<String>>,
        kind: Int = 1,
        replyToPubkey: String? = null,
        inboxPubkeys: Collection<String> = replyToPubkey?.let { listOf(it) } ?: emptyList(),
        onPublished: (() -> Unit)? = null
    ): Boolean {
        val notePublisher = getPublisher() ?: return false
        if (!notePublisher.isActive || signer.pubkeyHex != notePublisher.accountPubkey) return false
        cancel()
        val token = generation
        val difficulty = getDifficulty()
        val createdAt = System.currentTimeMillis() / 1000

        miningJob = notePublisher.launchWork {
            try {
                _status.value = PowStatus.Mining(kind, 0, difficulty)

                val result = withContext(miningDispatcher) {
                    Nip13.mine(
                        pubkeyHex = signer.pubkeyHex,
                        kind = kind,
                        content = content,
                        tags = tags,
                        targetDifficulty = difficulty,
                        createdAt = createdAt,
                        onProgress = { attempts ->
                            notePublisher.launchWork {
                                if (token == generation) _status.value = PowStatus.Mining(kind, attempts, difficulty)
                            }
                        }
                    )
                }

                val event = signer.signEvent(
                    kind = kind,
                    content = content,
                    tags = result.tags,
                    createdAt = result.createdAt
                )

                val publication = notePublisher.publish(event, inboxPubkeys)
                currentCoroutineContext().ensureActive()
                onPublished?.invoke()

                val accepted = publication.acceptedCount
                _status.value = if (accepted > 0) {
                    PowStatus.Done("Confirmed by $accepted relay${if (accepted != 1) "s" else ""}")
                } else {
                    PowStatus.Failed("No relay confirmed publication. Note saved; use Rebroadcast to retry.")
                }
                delay(3000)
                _status.value = PowStatus.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (token == generation) _status.value = PowStatus.Idle
                throw e
            } catch (e: Exception) {
                _status.value = PowStatus.Failed(e.message ?: "Mining failed")
                delay(3000)
                _status.value = PowStatus.Idle
            }
        }
        return true
    }

    fun cancel() {
        generation++
        miningJob?.cancel()
        miningJob = null
        _status.value = PowStatus.Idle
    }
}
