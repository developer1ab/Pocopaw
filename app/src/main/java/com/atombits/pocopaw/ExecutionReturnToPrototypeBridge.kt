package com.atombits.pocopaw

import android.content.Context
import android.content.Intent
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import java.util.Locale

internal enum class ExecutionReturnToPrototypeReason {
    COMPLETED,
    FAILED
}

private const val EXTRA_EXECUTION_RETURN_REASON = "com.atombits.pocopaw.extra.EXECUTION_RETURN_REASON"

internal fun resolveExecutionReturnToPrototypeReason(
    store: PrototypeStoreData
): ExecutionReturnToPrototypeReason? {
    val pendingExecutionRecovery = store.resolveCurrentState().pendingExecutionRecovery
    return when {
        store.latestCompletedProcessReviewContext != null -> ExecutionReturnToPrototypeReason.COMPLETED
        store.pendingProcessRecoveryContext != null -> ExecutionReturnToPrototypeReason.FAILED
        pendingExecutionRecovery != null -> resolveExecutionRecoveryReturnReason(pendingExecutionRecovery)
        else -> null
    }
}

private fun resolveExecutionRecoveryReturnReason(
    recovery: ProcessRecoveryContext
): ExecutionReturnToPrototypeReason {
    val action = recovery.recoveryAction.lowercase(Locale.US)
    return if (
        action.contains("failed") ||
        action.contains("failure") ||
        action.contains("retry")
    ) {
        ExecutionReturnToPrototypeReason.FAILED
    } else {
        ExecutionReturnToPrototypeReason.COMPLETED
    }
}

internal fun requestExecutionReturnToPrototypeIfNeeded(
    store: PrototypeStoreData,
    launcher: (ExecutionReturnToPrototypeReason) -> Unit
): ExecutionReturnToPrototypeReason? {
    val reason = resolveExecutionReturnToPrototypeReason(store) ?: return null
    launcher(reason)
    return reason
}

internal fun launchExecutionReturnToPrototype(
    context: Context,
    reason: ExecutionReturnToPrototypeReason
) {
    context.startActivity(buildExecutionReturnToPrototypeIntent(context, reason))
}

internal fun buildExecutionReturnToPrototypeIntent(
    context: Context,
    reason: ExecutionReturnToPrototypeReason
): Intent {
    return Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(EXTRA_EXECUTION_RETURN_REASON, reason.name)
    }
}

internal fun isExecutionReturnToPrototypeIntent(intent: Intent?): Boolean {
    return intent?.hasExtra(EXTRA_EXECUTION_RETURN_REASON) == true
}