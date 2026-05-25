package com.atombits.pocopaw

import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessReviewContext
import com.atombits.pocopaw.process.runtime.ProcessRuntimeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ExecutionTimelineItem(
    val id: String,
    val leadingText: String,
    val stateText: String,
    val summary: String,
    val detail: String? = null
)

internal data class ExecutionSurfaceSummary(
    val overview: String,
    val route: String
)

internal object ConsoleExecutionUiFormatter {

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun buildExecutionSurfaceSummary(store: PrototypeStoreData): ExecutionSurfaceSummary {
        val currentState = store.resolveCurrentState()
        val taskRecord = currentState.currentTaskRecord
        val runtime = store.resolveCurrentProcessRuntime()
        val currentRuntime = store.resolveCurrentExecutionRuntime()
        val boundaryPacket = resolveStoreAwareExecutionBoundaryPacket(store, currentState)
        val latestTrace = currentRuntime?.executionTrace ?: store.resolveExecutionTraces().maxByOrNull { trace -> trace.startedAt }
        val routeMap = parseRouteInfo(currentRuntime?.executionResult?.routeInfo)
        val routeHistory = currentRuntime?.routeDecisionHistorySnapshot()
            ?.takeIf { history -> history.isNotEmpty() }
            ?: store.resolvePendingProcessRecoveryContext()?.routeDecisionHistory?.takeIf { history -> history.isNotEmpty() }
            ?: store.resolveLatestCompletedProcessReviewContext()?.routeDecisionHistory.orEmpty()
        val sessionEvents = sessionEvents(store, latestTrace?.startedAt)

        val overview = buildList {
            runtime?.let { state ->
                add("runtime_status: ${state.status.name}")
                if (state.currentStep > 0) {
                    add("current_action: ${state.currentStep}/${state.maxSteps}")
                }
                state.blockedContext?.takeIf { it.isNotBlank() }?.let { add("blocked_context: ${compactSentence(it)}") }
            }
            resolveDisplayObjective(taskRecord, boundaryPacket)?.let { add("objective: ${compactSentence(it)}") }
            resolveDisplayPlan(taskRecord, boundaryPacket)?.let { add("plan: ${compactPlanSummary(it)}") }
            store.resolvePendingProcessRecoveryContext()?.blockedContext?.takeIf { it.isNotBlank() }?.let { blocked ->
                add("recovery_needed: ${compactSentence(blocked)}")
            }
            currentRuntime?.executionResult?.summary?.takeIf { it.isNotBlank() }?.let { add("latest_summary: ${compactSentence(it)}") }
                ?: sessionEvents.lastOrNull()?.summary?.takeIf { it.isNotBlank() }?.let { add("latest_summary: ${compactSentence(it)}") }
        }.ifEmpty {
            listOf(
                UiStrings.resolve(
                    R.string.execution_surface_summary_empty,
                    "No active execution runtime. Start execution to see the current route summary."
                )
            )
        }.joinToString("\n")

        val route = buildList {
            addAll(buildRouteAttemptDetails(routeHistory, separator = ": "))
            buildRouteHeadline(runtime, taskRecord, boundaryPacket, routeMap)?.let(::add)
            runtime?.matchedReadyAssetName?.takeIf { it.isNotBlank() }?.let { add("ready_asset: $it") }
            resolveDisplayProcess(taskRecord, boundaryPacket)?.let { process -> add("selected_process: $process") }
            buildBoundarySlotSummary(boundaryPacket)?.let(::add)
            currentRuntime?.preferenceRecallDebugSnapshot?.let { snapshot ->
                add("preference_recall: ${compactSentence(snapshot.summaryLine())}")
            }
            currentRuntime?.preferenceMappingTrace?.let { trace ->
                add("preference_mapping: ${compactSentence(trace.summaryLine())}")
            }
            buildRouteTraceSummary(taskRecord, boundaryPacket)?.let(::add)
            buildContinuationSummary(store)?.let(::add)
            runtime?.processGuidanceLayerSummary?.takeIf { it.isNotBlank() }?.let { add("guidance: ${compactSentence(it)}") }
        }.ifEmpty {
            listOf(
                UiStrings.resolve(
                    R.string.execution_route_summary_empty,
                    "No route summary yet."
                )
            )
        }.joinToString("\n")

        return ExecutionSurfaceSummary(overview = overview, route = route)
    }

    fun buildExecutionTimeline(store: PrototypeStoreData): List<ExecutionTimelineItem> {
        val items = mutableListOf<ExecutionTimelineItem>()
        val currentRuntime = store.resolveCurrentExecutionRuntime()
        val latestTrace = currentRuntime?.executionTrace ?: store.resolveExecutionTraces().maxByOrNull { trace -> trace.startedAt }
        val sessionEvents = sessionEvents(store, latestTrace?.startedAt)
        val runtime = store.resolveCurrentProcessRuntime()
        val routeInfo = currentRuntime?.executionResult?.routeInfo

        if (latestTrace != null) {
            val startEvent = sessionEvents.firstOrNull { event ->
                resolveExecutionEventPhase(event.phase, event.lifecycleStatus, event.summary) == ExecutionEventPhase.STARTING
            }
            items += ExecutionTimelineItem(
                id = "start-${latestTrace.traceId}",
                leadingText = formatTime(startEvent?.startedAt ?: latestTrace.startedAt),
                stateText = UiStrings.resolve(R.string.execution_phase_starting, "START"),
                summary = startEvent?.summary ?: UiStrings.resolve(
                    R.string.execution_timeline_started,
                    "Execution started"
                ),
                detail = buildStartDetail(store, routeInfo)
            )

            var actionNumber = 0
            latestTrace.steps.drop(1).forEachIndexed { index, step ->
                if (isActionStep(step)) {
                    actionNumber += 1
                    items += ExecutionTimelineItem(
                        id = "action-${latestTrace.traceId}-$index",
                        leadingText = String.format(Locale.US, "#%02d", actionNumber),
                        stateText = UiStrings.resolve(R.string.execution_timeline_state_action, "ACT"),
                        summary = UiStrings.resolve(
                            R.string.execution_timeline_action_summary,
                            "Action %1\$d · %2\$s",
                            actionNumber,
                            formatStepLabel(step)
                        ),
                        detail = buildActionDetail(step, actionNumber, runtime)
                    )
                } else {
                    items += ExecutionTimelineItem(
                        id = "check-${latestTrace.traceId}-$index",
                        leadingText = UiStrings.resolve(R.string.execution_timeline_state_check, "CHK"),
                        stateText = if (looksFailed(step.note) || looksFailed(step.expectedOutcome)) {
                            UiStrings.resolve(R.string.execution_state_failed, "FAIL")
                        } else {
                            UiStrings.resolve(R.string.execution_timeline_state_check, "CHK")
                        },
                        summary = buildCheckSummary(step),
                        detail = buildCheckDetail(step)
                    )
                }
            }
        }

        sessionEvents.filter(::isHighSignalEvent).forEachIndexed { index, event ->
            items += ExecutionTimelineItem(
                id = "event-${event.id}-$index",
                leadingText = formatTime(event.startedAt),
                stateText = formatEventState(event),
                summary = event.summary,
                detail = compactDetail(event.keyInfo)
            )
        }

        store.resolvePendingProcessRecoveryContext()?.let { recovery ->
            items += ExecutionTimelineItem(
                id = "recovery-${recovery.recoveryId}",
                leadingText = formatTime(recovery.createdAt),
                stateText = UiStrings.resolve(R.string.execution_timeline_state_guide, "GUIDE"),
                summary = UiStrings.resolve(
                    R.string.execution_timeline_waiting_guidance,
                    "Waiting for user guidance"
                ),
                detail = buildRecoveryDetail(recovery)
            )
        }

        store.resolveLatestCompletedProcessReviewContext()?.let { review ->
            items += ExecutionTimelineItem(
                id = "review-${review.reviewId}",
                leadingText = formatTime(review.reviewedAt),
                stateText = UiStrings.resolve(R.string.execution_state_done, "DONE"),
                summary = UiStrings.resolve(
                    R.string.execution_timeline_review_ready,
                    "Execution review ready"
                ),
                detail = buildReviewDetail(review)
            )
        }

        return items.ifEmpty {
            store.resolveExecutionEvents().map { event ->
                ExecutionTimelineItem(
                    id = event.id,
                    leadingText = formatTime(event.startedAt),
                    stateText = formatEventState(event),
                    summary = event.summary,
                    detail = compactDetail(event.keyInfo)
                )
            }
        }
    }

    private fun buildStartDetail(store: PrototypeStoreData, routeInfo: String?): String {
        val taskRecord = store.resolveCurrentState().currentTaskRecord
        val runtime = store.resolveCurrentProcessRuntime()
        val boundaryPacket = resolveStoreAwareExecutionBoundaryPacket(store)
        val executionRuntime = store.resolveCurrentExecutionRuntime()
        val routeHeadline = buildRouteHeadline(runtime, taskRecord, boundaryPacket, parseRouteInfo(routeInfo))
        return buildList {
            resolveDisplayObjective(taskRecord, boundaryPacket)?.let { add("objective=$it") }
            resolveDisplayPlan(taskRecord, boundaryPacket)?.let { add("plan=${compactPlanSummary(it)}") }
            routeHeadline?.let(::add)
            addAll(buildRouteAttemptDetails(executionRuntime?.routeDecisionHistorySnapshot().orEmpty(), separator = "="))
        }.joinToString(" | ").ifBlank { "runtime_started" }
    }

    private fun buildActionDetail(
        step: ExecutionTraceStep,
        actionNumber: Int,
        runtime: ProcessRuntimeState?
    ): String {
        return buildList {
            add("action_index=$actionNumber")
            add("grounding=${step.groundingMode}")
            step.expectedOutcome.takeIf { it.isNotBlank() }?.let { add("expected=$it") }
            step.note?.takeIf { it.isNotBlank() }?.let { add("note=${compactSentence(it)}") }
            if (runtime != null && runtime.currentStep == actionNumber) {
                add("current_action=true")
            }
        }.joinToString(" | ")
    }

    private fun buildCheckSummary(step: ExecutionTraceStep): String {
        val expected = step.expectedOutcome.takeIf { it.isNotBlank() }
        val note = step.note?.takeIf { it.isNotBlank() }
        return when {
            looksFailed(note) -> UiStrings.resolve(R.string.execution_check_failed, "Verification failed")
            looksFailed(expected) -> UiStrings.resolve(R.string.execution_check_failed, "Verification failed")
            expected != null -> UiStrings.resolve(
                R.string.execution_check_expected,
                "Verification · %1\$s",
                expected
            )
            note != null -> UiStrings.resolve(
                R.string.execution_check_note,
                "Verification · %1\$s",
                compactSentence(note)
            )
            else -> UiStrings.resolve(R.string.execution_check_default, "Verification")
        }
    }

    private fun buildCheckDetail(step: ExecutionTraceStep): String {
        return buildList {
            add("grounding=${step.groundingMode}")
            step.note?.takeIf { it.isNotBlank() }?.let { add("note=${compactSentence(it)}") }
            if (step.verificationSignals.isNotEmpty()) {
                add("signals=${step.verificationSignals.take(3).joinToString(",")}")
            }
        }.joinToString(" | ")
    }

    private fun buildRecoveryDetail(recovery: ProcessRecoveryContext): String {
        return buildList {
            add("objective=${recovery.objective}")
            add("blocked=${compactSentence(recovery.blockedContext)}")
            add("recovery_action=${recovery.recoveryAction}")
            addAll(buildRouteAttemptDetails(recovery.routeDecisionHistory, separator = "="))
            if (recovery.retryBudget > 0) {
                add("retry_budget=${recovery.retryBudget}")
            }
        }.joinToString(" | ")
    }

    private fun buildReviewDetail(review: ProcessReviewContext): String {
        return buildList {
            review.processAssetName?.takeIf { it.isNotBlank() }?.let { add("asset=$it") }
            review.finalUserSummary?.takeIf { it.isNotBlank() }?.let { add("summary=${compactSentence(it)}") }
            review.verificationSummary?.takeIf { it.isNotBlank() }?.let { add("route=${compactSentence(it)}") }
            addAll(buildRouteAttemptDetails(review.routeDecisionHistory, separator = "="))
        }.joinToString(" | ")
    }

    private fun buildRouteAttemptDetails(
        routeHistory: List<RouteDecisionRecord>,
        separator: String
    ): List<String> {
        if (routeHistory.isEmpty()) {
            return emptyList()
        }
        return buildList {
            add("route_attempt$separator${compactSentence(routeHistory.last().summaryLine())}")
            if (routeHistory.size > 1) {
                add("route_attempt_history$separator${compactSentence(routeHistory.historySummaryLine())}")
            }
        }
    }

    private fun buildRouteHeadline(
        runtime: ProcessRuntimeState?,
        taskRecord: TaskRecord?,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        routeMap: Map<String, String>
    ): String? {
        val routeType = routeMap["route"]
        val process = taskRecord?.processId
            ?: boundaryPacket?.processId
            ?: routeMap["route_process"]
        val readyAsset = runtime?.matchedReadyAssetName
        val routeTarget = taskRecord?.resolvePreferredAppScope()
        return when {
            readyAsset != null -> "route=reusable_process | asset=$readyAsset"
            routeType != null && process != null -> "route=$routeType | process=$process"
            routeType != null -> "route=$routeType"
            routeTarget != null && process != null -> "route=$routeTarget | process=$process"
            routeTarget != null -> "route=$routeTarget"
            process != null -> "process=$process"
            else -> null
        }
    }

    private fun buildRouteTraceSummary(
        taskRecord: TaskRecord?,
        boundaryPacket: TaskExecutionBoundaryPacket?
    ): String? {
        if (taskRecord != null) {
            val parts = buildList {
                taskRecord.capabilityStack?.let { add("stack=${it.name}") }
                taskRecord.capabilityDomain?.let { add("domain=${it.wireName}") }
                taskRecord.resolvePreferredAppScope()?.takeIf { it.isNotBlank() }?.let { add("target=$it") }
                taskRecord.processId?.takeIf { it.isNotBlank() }?.let { add("process=$it") }
                taskRecord.reasonSummary?.takeIf { it.isNotBlank() }?.let { add("reason=${compactSentence(it)}") }
            }
            parts.takeIf { it.isNotEmpty() }?.let { values ->
                return values.joinToString(prefix = "route_trace: ", separator = " | ")
            }
        }
        val parts = buildList {
            extractCanonicalAppScope(boundaryPacket?.capabilityId)?.takeIf { it.isNotBlank() }?.let { add("app_scope=$it") }
            boundaryPacket?.capabilityId?.takeIf { it.isNotBlank() }?.let { add("tool=$it") }
            boundaryPacket?.processId?.takeIf { it.isNotBlank() }?.let { add("process=$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "route_trace: ", separator = " | ")
    }

    private fun buildBoundarySlotSummary(boundaryPacket: TaskExecutionBoundaryPacket?): String? {
        val packet = boundaryPacket ?: return null
        val parts = buildList {
            if (packet.requiredDetailSlots.isNotEmpty()) {
                add("required=${packet.requiredDetailSlots.joinToString(prefix = "[", postfix = "]") { it.contractName }}")
            }
            val resolved = buildList {
                packet.targetKey.trim().takeIf { it.isNotBlank() }?.let { target -> add("target_object=$target") }
                packet.detailSlots.forEach { (key, value) ->
                    value.trim().takeIf { it.isNotBlank() }?.let { resolvedValue -> add("${key.contractName}=$resolvedValue") }
                }
            }
            if (resolved.isNotEmpty()) {
                add("resolved=${resolved.joinToString(prefix = "[", postfix = "]")}")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "boundary_slots: ", separator = " | ")
    }

    private fun resolveDisplayObjective(
        taskRecord: TaskRecord?,
        boundaryPacket: TaskExecutionBoundaryPacket?
    ): String? {
        return taskRecord?.displayTarget()?.takeIf { it.isNotBlank() }
            ?: boundaryPacket?.objectiveSummary?.takeIf { it.isNotBlank() }
    }

    private fun resolveDisplayPlan(
        taskRecord: TaskRecord?,
        boundaryPacket: TaskExecutionBoundaryPacket?
    ): String? {
        return taskRecord?.displayPlanSummary().takeIf { it?.isNotBlank() == true }
            ?: boundaryPacket?.planSummary?.takeIf { it.isNotBlank() }
    }

    private fun resolveDisplayProcess(
        taskRecord: TaskRecord?,
        boundaryPacket: TaskExecutionBoundaryPacket?
    ): String? {
        return taskRecord?.processId?.takeIf { it.isNotBlank() }
            ?: boundaryPacket?.processId?.takeIf { it.isNotBlank() }
    }

    private fun buildContinuationSummary(store: PrototypeStoreData): String? {
        val taskEvidence = ContinuationGroundingResolver.buildTaskContinuationEvidence(
            store = store,
            now = store.resolveCurrentState().lastUpdatedAt.takeIf { timestamp -> timestamp > 0L } ?: System.currentTimeMillis()
        )
        if (taskEvidence == null) {
            return null
        }
        return buildList {
            taskEvidence.activeTaskId?.takeIf { value -> value.isNotBlank() }?.let { add("active_task_id=${compactSentence(it)}") }
            add("summary=${compactSentence(taskEvidence.summary)}")
            taskEvidence.preferredAppScope?.takeIf { value -> value.isNotBlank() }?.let { add("app_scope=${compactSentence(it)}") }
            taskEvidence.targetObject?.takeIf { value -> value.isNotBlank() }?.let { add("target_object=${compactSentence(it)}") }
            taskEvidence.checkpoint?.checkpointId?.takeIf { value -> value.isNotBlank() }?.let { add("checkpoint=${compactSentence(it)}") }
        }.joinToString(prefix = "continuation_evidence: ", separator = " | ")
    }

    private fun compactPlanSummary(planSummary: String): String {
        return planSummary.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun compactSentence(value: String): String {
        return value.replace('\n', ' ').replace('|', '/').replace(Regex("\\s+"), " ").trim()
    }

    private fun compactDetail(value: String?): String? {
        val compact = value?.let(::compactSentence).orEmpty()
        return compact.ifBlank { null }
    }

    private fun sessionEvents(store: PrototypeStoreData, startedAt: Long?): List<ExecutionEvent> {
        val executionEvents = store.resolveExecutionEvents()
        val events = if (startedAt != null) {
            executionEvents.filter { event -> event.startedAt >= startedAt }
        } else {
            executionEvents
        }
        return events.sortedBy { event -> event.startedAt }
    }

    private fun isActionStep(step: ExecutionTraceStep): Boolean {
        return step.stepType !in setOf("START", "VERIFY")
    }

    private fun formatStepLabel(step: ExecutionTraceStep): String {
        val base = step.stepType.replace('_', ' ').lowercase(Locale.US)
            .split(' ')
            .joinToString(" ") { token -> token.replaceFirstChar { character -> character.uppercase(Locale.US) } }
        return step.note?.takeIf { it.isNotBlank() }?.let { note ->
            "$base (${compactSentence(note)})"
        } ?: base
    }

    private fun looksFailed(value: String?): Boolean {
        return value?.contains("fail", ignoreCase = true) == true ||
            value?.contains("blocked", ignoreCase = true) == true
    }

    private fun isHighSignalEvent(event: ExecutionEvent): Boolean {
        val phase = resolveExecutionEventPhase(event.phase, event.lifecycleStatus, event.summary)
        val summary = event.summary
        return phase == ExecutionEventPhase.COMPLETED ||
            phase == ExecutionEventPhase.FAILED ||
            summary.contains("replan", ignoreCase = true) ||
            summary.contains("replanning", ignoreCase = true) ||
            summary.contains("retry", ignoreCase = true) ||
            summary.contains("max step", ignoreCase = true) ||
            summary.contains("guidance", ignoreCase = true) ||
            summary.contains("unavailable", ignoreCase = true)
    }

    private fun formatEventState(event: ExecutionEvent): String {
        val facts = parseFactMap(event.keyInfo)
        val phase = resolveExecutionEventPhase(event.phase, event.lifecycleStatus, event.summary)
        return when {
            facts["event_type"] == "replan" -> UiStrings.resolve(R.string.execution_timeline_state_plan, "PLAN")
            facts["event_type"] == "action_failure" -> UiStrings.resolve(R.string.execution_state_failed, "FAIL")
            event.summary.contains("replan", ignoreCase = true) || event.summary.contains("replanning", ignoreCase = true) -> {
                UiStrings.resolve(R.string.execution_timeline_state_plan, "PLAN")
            }
            event.summary.contains("retry", ignoreCase = true) -> UiStrings.resolve(R.string.execution_timeline_state_retry, "RETRY")
            phase == ExecutionEventPhase.STARTING -> UiStrings.resolve(R.string.execution_phase_starting, "START")
            phase == ExecutionEventPhase.RUNNING -> UiStrings.resolve(R.string.execution_phase_info, "INFO")
            phase == ExecutionEventPhase.COMPLETED -> UiStrings.resolve(R.string.execution_state_done, "DONE")
            phase == ExecutionEventPhase.FAILED -> UiStrings.resolve(R.string.execution_state_failed, "FAIL")
            else -> UiStrings.resolve(R.string.execution_phase_info, "INFO")
        }
    }

    private fun parseRouteInfo(routeInfo: String?): Map<String, String> {
        if (routeInfo.isNullOrBlank()) {
            return emptyMap()
        }
        return routeInfo.split('|')
            .map { token -> token.trim() }
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
                }
            }
            .toMap()
    }

    private fun parseFactMap(raw: String?): Map<String, String> {
        return parseRouteInfo(raw)
    }

    private fun formatTime(timestamp: Long): String {
        return timeFormatter.format(Date(timestamp))
    }
}