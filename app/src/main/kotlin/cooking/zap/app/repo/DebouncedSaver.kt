package cooking.zap.app.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-key debounced-save scheduler — the reusable primitive behind grocery's
 * (and, later, the planner's) debounced publish. Mirrors web groceryStore's
 * per-list-id timer map with `SAVE_DEBOUNCE_MS = 2000`: scheduling a key
 * cancels that key's pending job and restarts the timer, so a burst of
 * mutations coalesces into ONE save.
 *
 * Android-free (takes a [CoroutineScope] + a suspend save fn) so the coalescing
 * behavior is hermetically testable with virtual time, per the repo's
 * pure-logic test convention.
 */
class DebouncedSaver(
    private val scope: CoroutineScope,
    private val delayMs: Long = 2_000L,
    private val save: suspend (key: String) -> Unit,
) {
    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    /** Schedule (or reschedule) a save for [key] after [delayMs] of quiet. */
    fun schedule(key: String) {
        synchronized(lock) {
            jobs.remove(key)?.cancel()
            jobs[key] = scope.launch {
                delay(delayMs)
                // Drop our own registration before saving so a mutation arriving
                // mid-save schedules a fresh follow-up rather than being ignored.
                synchronized(lock) { jobs.remove(key) }
                save(key)
            }
        }
    }

    /** True while [key] has a save pending (used to protect a dirty list on refresh). */
    fun isPending(key: String): Boolean = synchronized(lock) { jobs.containsKey(key) }

    /**
     * Cancel [key]'s pending timer and save it immediately. No-op when nothing is
     * pending for [key] (already flushed / never scheduled).
     */
    suspend fun flush(key: String) {
        val had = synchronized(lock) { jobs.remove(key)?.also { it.cancel() } != null }
        if (had) save(key)
    }

    /** Cancel [key]'s pending save without saving (e.g. the list was deleted). */
    fun cancel(key: String) {
        synchronized(lock) { jobs.remove(key)?.cancel() }
    }

    /** Cancel every pending save without saving — logout / account switch. */
    fun cancelAll() {
        synchronized(lock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }
}
