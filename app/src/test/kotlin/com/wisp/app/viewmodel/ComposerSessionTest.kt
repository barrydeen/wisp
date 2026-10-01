package com.wisp.app.viewmodel

import org.junit.Assert.*
import org.junit.Test

class ComposerSessionTest {
    @Test fun `completion after leaving cannot change the current screen`() {
        val editor = ComposerSession()
        val token = editor.begin()
        var screen = "compose"
        editor.end(token)
        screen = "profile"
        editor.complete(token) { screen = "feed" }
        assertEquals("profile", screen)
    }

    @Test fun `old completion cannot clear a later draft`() {
        val editor = ComposerSession()
        val token = editor.begin()
        editor.begin()
        var draft = "new draft"
        editor.complete(token) { draft = "" }
        assertEquals("new draft", draft)
    }

    @Test fun `old disposal cannot invalidate the new editor`() {
        val editor = ComposerSession()
        val old = editor.begin()
        val current = editor.begin()
        editor.end(old)
        assertTrue(editor.isCurrent(current))
    }

    @Test fun `clear or loading a draft invalidates pending completion`() {
        val editor = ComposerSession()
        val old = editor.begin()
        editor.invalidate()
        var changed = false
        editor.complete(old) { changed = true }
        assertFalse(changed)
    }

    @Test fun `current editor can complete normally`() {
        val editor = ComposerSession()
        val token = editor.begin()
        var completed = false
        editor.complete(token) { completed = true }
        assertTrue(completed)
    }
    @Test fun `editing while save waits invalidates the old completion`() {
        val editor = ComposerSession()
        val screen = editor.begin()
        val pending = editor.token()
        editor.revise()
        var draft = "edited draft"
        editor.complete(pending) { draft = "" }
        assertEquals("edited draft", draft)
        assertTrue(editor.isCurrent(editor.token()))
        editor.end(screen)
        assertFalse(editor.isCurrent(editor.token()))
    }

}
