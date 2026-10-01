package com.wisp.app.viewmodel

/** Completions belong to one screen and draft revision. Used on the main thread. */
class ComposerSession {
    data class Token(val editor: Long, val revision: Long)
    private var generation = 0L
    private var revision = 0L
    private var active = false

    fun begin(): Token {
        active = true
        generation++
        revision = 0
        return token()
    }

    fun invalidate() { active = false; generation++ }
    fun revise() { revision++ }
    fun owns(token: Token): Boolean = active && token.editor == generation
    fun end(token: Token) { if (owns(token)) invalidate() }
    fun token(): Token = Token(generation, revision)
    fun isCurrent(token: Token): Boolean = owns(token) && token.revision == revision
    fun complete(token: Token, action: () -> Unit) { if (isCurrent(token)) action() }
}
