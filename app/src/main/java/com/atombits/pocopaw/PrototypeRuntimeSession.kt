package com.atombits.pocopaw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PrototypeRuntimeSession {
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeExecutionJob: Job? = null

    val startedAt: Long = System.currentTimeMillis()

    @Synchronized
    fun launchExecutionOrNull(block: suspend () -> Unit): Job? {
        if (activeExecutionJob?.isActive == true) {
            return null
        }
        val launchedJob = executionScope.launch {
            block()
        }
        activeExecutionJob = launchedJob
        launchedJob.invokeOnCompletion {
            synchronized(this) {
                if (activeExecutionJob === launchedJob) {
                    activeExecutionJob = null
                }
            }
        }
        return launchedJob
    }

    @Synchronized
    fun isExecutionRunning(): Boolean {
        return activeExecutionJob?.isActive == true
    }
}