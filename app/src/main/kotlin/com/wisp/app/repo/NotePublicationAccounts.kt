package com.wisp.app.repo

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The active publisher is disposable; recovery files remain owned by their account. */
class NotePublicationAccounts(private val create: (String, suspend () -> Unit) -> NotePublisher) {
    private val _publisher = MutableStateFlow<NotePublisher?>(null)
    val publisher: StateFlow<NotePublisher?> = _publisher

    private val retiring = mutableListOf<Job>()

    fun switchAccount(pubkey: String?) {
        _publisher.value?.close()?.let { retiring.add(it) }
        retiring.removeAll { it.isCompleted }
        _publisher.value = null
        val predecessors = retiring.toList()
        if (pubkey != null) _publisher.value = create(pubkey) { predecessors.forEach { it.join() } }
    }
}
