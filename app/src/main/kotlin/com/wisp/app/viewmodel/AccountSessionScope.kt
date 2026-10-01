package com.wisp.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlin.coroutines.CoroutineContext

/** Keeps account work cancellable without cancelling the owning ViewModel. */
internal class AccountSessionScope(private val parent: CoroutineScope) : CoroutineScope {
    private var job = SupervisorJob(parent.coroutineContext[Job])
    override val coroutineContext: CoroutineContext
        get() = parent.coroutineContext + job

    suspend fun stop() {
        job.cancelAndJoin()
    }

    fun start() {
        if (!job.isActive) job = SupervisorJob(parent.coroutineContext[Job])
    }
}
