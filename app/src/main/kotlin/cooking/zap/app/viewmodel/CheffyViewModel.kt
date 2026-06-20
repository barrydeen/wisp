package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.CheffyMessage
import cooking.zap.app.api.CheffyMode
import cooking.zap.app.api.CheffyRequest
import cooking.zap.app.api.CheffyResult
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.cheffy.Cheffy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [cooking.zap.app.ui.screen.CheffyScreen] — the member-gated Cheffy chat
 * (concern 2.3 v1: chat + hungry, pure conversation).
 *
 * **Stateless full-history**, mirroring the web `cheffyChat` store: the live
 * thread lives here as a [StateFlow]; every send maps the non-pending/non-error
 * messages to `{role, content}` and re-sends them with the new prompt. Nothing
 * is persisted (no DB, no Nostr) — "Start over" clears it. The server caps
 * history to 12 turns; we mirror that client-side ([Cheffy.MAX_HISTORY_TURNS]).
 */
class CheffyViewModel : ViewModel() {

    enum class Role { USER, CHEFFY }
    enum class Kind { TEXT, RECIPE, PENDING, ERROR, MEMBERS_ONLY }

    data class Message(
        val id: Long,
        val role: Role,
        val content: String,
        val kind: Kind,
        val expression: Cheffy.Expression = Cheffy.Expression.NEUTRAL,
        val statusLine: String? = null,
    )

    private val _thread = MutableStateFlow<List<Message>>(emptyList())
    val thread: StateFlow<List<Message>> = _thread

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var lastStatusLine: String = ""
    private var lastTurn: Pair<String, CheffyMode>? = null // (prompt, mode) for retry

    /**
     * Map the visible thread to the API history shape, mirroring the web
     * `buildHistory`: only resolved text/recipe turns, optionally dropping the
     * trailing user turn (retry re-sends it as the fresh prompt), capped to the
     * server's last-N turns.
     */
    private fun buildHistory(excludeTrailingUser: Boolean = false): List<CheffyMessage> {
        val api = _thread.value
            .filter { (it.kind == Kind.TEXT || it.kind == Kind.RECIPE) && it.content.isNotBlank() }
            .map { CheffyMessage(role = if (it.role == Role.CHEFFY) "assistant" else "user", content = it.content) }
            .toMutableList()
        if (excludeTrailingUser && api.isNotEmpty() && api.last().role == "user") {
            api.removeAt(api.lastIndex)
        }
        return api.takeLast(Cheffy.MAX_HISTORY_TURNS)
    }

    /**
     * Send a turn. [pubkey] is the signing account's (READ_ONLY is gated by the
     * screen and never reaches here). For [CheffyMode.HUNGRY], [content] is
     * ignored and the server supplies the prompt.
     */
    fun send(content: String, mode: CheffyMode, api: ZapCookingApi, pubkey: String?) {
        if (_loading.value) return
        if (pubkey == null) return // READ_ONLY — gated upstream
        val text = content.trim().take(Cheffy.MAX_PROMPT_CHARS)
        if (mode != CheffyMode.HUNGRY && text.isEmpty()) return

        // Capture history BEFORE adding this user turn (mirrors the web order).
        val history = buildHistory()
        val display = if (mode == CheffyMode.HUNGRY) "Surprise me 🎲" else text
        _thread.update { it + Message(nextId(), Role.USER, display, Kind.TEXT) }
        lastTurn = (if (mode == CheffyMode.HUNGRY) "" else text) to mode

        val promptForApi = if (mode == CheffyMode.HUNGRY) "" else text
        val expectRecipe = mode == CheffyMode.HUNGRY || Cheffy.looksLikeRecipeRequest(text)
        dispatch(promptForApi, mode, history, expectRecipe, api, pubkey)
    }

    /** Re-send the last turn after an error (drop the error bubble first). */
    fun retry(api: ZapCookingApi, pubkey: String?) {
        if (_loading.value || pubkey == null) return
        val (prompt, mode) = lastTurn ?: return
        _thread.update { list -> list.filterNot { it.kind == Kind.ERROR } }
        val history = buildHistory(excludeTrailingUser = true)
        val expectRecipe = mode == CheffyMode.HUNGRY || Cheffy.looksLikeRecipeRequest(prompt)
        dispatch(prompt, mode, history, expectRecipe, api, pubkey)
    }

    private fun dispatch(
        prompt: String,
        mode: CheffyMode,
        history: List<CheffyMessage>,
        expectRecipe: Boolean,
        api: ZapCookingApi,
        pubkey: String,
    ) {
        _loading.value = true
        val statusLine = Cheffy.pickLine(
            if (expectRecipe) Cheffy.COOKING_LINES else Cheffy.THINKING_LINES,
            lastStatusLine,
        )
        lastStatusLine = statusLine
        val pendingId = nextId()
        _thread.update {
            it + Message(
                id = pendingId,
                role = Role.CHEFFY,
                content = "",
                kind = Kind.PENDING,
                expression = if (expectRecipe) Cheffy.Expression.COOKING else Cheffy.Expression.THINKING,
                statusLine = statusLine,
            )
        }
        viewModelScope.launch {
            try {
                val result = api.sendCheffy(
                    CheffyRequest(prompt = prompt, mode = mode.wire, pubkey = pubkey, messages = history),
                )
                val resolved = when (result) {
                    is CheffyResult.Reply -> {
                        val isRecipe = Cheffy.looksLikeStructuredRecipe(result.output)
                        Message(
                            id = pendingId, role = Role.CHEFFY, content = result.output,
                            kind = if (isRecipe) Kind.RECIPE else Kind.TEXT,
                            expression = if (isRecipe) Cheffy.Expression.HAPPY else Cheffy.Expression.NEUTRAL,
                        )
                    }
                    CheffyResult.MembersOnly -> Message(
                        id = pendingId, role = Role.CHEFFY, content = Cheffy.MEMBERS_ONLY_MESSAGE,
                        kind = Kind.MEMBERS_ONLY, expression = Cheffy.Expression.NEUTRAL,
                    )
                    is CheffyResult.Error -> Message(
                        id = pendingId, role = Role.CHEFFY, content = result.message,
                        kind = Kind.ERROR, expression = Cheffy.Expression.CONCERNED,
                        statusLine = Cheffy.pickLine(Cheffy.ERROR_LINES),
                    )
                }
                _thread.update { list -> list.map { if (it.id == pendingId) resolved else it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled mid-flight (e.g. navigated away) — drop the stranded
                // pending bubble so a restored screen never shows a frozen spinner.
                _thread.update { list -> list.filterNot { it.id == pendingId } }
                throw e
            } finally {
                // Always clear loading, even on cancellation, so the composer re-enables.
                _loading.value = false
            }
        }
    }

    fun startOver() {
        if (_loading.value) return
        _thread.value = emptyList()
        lastTurn = null
    }

    companion object {
        private var counter = 0L
        @Synchronized private fun nextId(): Long = ++counter
    }
}
