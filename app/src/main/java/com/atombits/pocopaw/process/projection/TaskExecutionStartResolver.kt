package com.atombits.pocopaw.process.projection

import com.atombits.pocopaw.R
import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.LocalConversationState
import com.atombits.pocopaw.TaskPhase
import com.atombits.pocopaw.UiStrings
import com.atombits.pocopaw.toTaskExecutionBoundaryPacket

data class TaskExecutionStartDecision(
    val canStart: Boolean,
    val blockReason: String? = null,
    val userMessage: String? = null,
    val executionBoundaryPacket: TaskExecutionBoundaryPacket? = null
)

class TaskExecutionStartResolver {

    fun resolve(currentState: LocalConversationState): TaskExecutionStartDecision {
        val taskRecord = currentState.currentTaskRecord ?: return TaskExecutionStartDecision(
            canStart = false,
            blockReason = "missing_task_record",
            userMessage = UiStrings.resolve(
                R.string.task_execution_missing_structured_task,
                "No structured task is ready to execute yet."
            )
        )
        if (taskRecord.phase != TaskPhase.EXECUTING) {
            return TaskExecutionStartDecision(
                canStart = false,
                blockReason = "task_record_not_executing",
                userMessage = UiStrings.resolve(
                    R.string.task_execution_not_in_execution_phase,
                    "The current task has not entered execution yet. I will keep refining it first."
                )
            )
        }
        return TaskExecutionStartDecision(
            canStart = true,
            executionBoundaryPacket = taskRecord.toTaskExecutionBoundaryPacket()
        )
    }
}
