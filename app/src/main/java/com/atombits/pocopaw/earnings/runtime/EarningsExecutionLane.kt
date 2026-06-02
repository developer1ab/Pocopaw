package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.DispatchResult
import com.atombits.pocopaw.earnings.EarningsDispatchRequest
import com.atombits.pocopaw.earnings.EarningsExecutionLaneState
import com.atombits.pocopaw.earnings.EarningsLaneStatus
import com.atombits.pocopaw.earnings.EarningsLaneTickResult
import com.atombits.pocopaw.earnings.ExecutionLedgerEntry
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.FillerCandidateRecord
import com.atombits.pocopaw.earnings.ImportantOccurrence
import com.atombits.pocopaw.earnings.ImportantOccurrenceStatus
import com.atombits.pocopaw.earnings.RewardBaseline
import com.atombits.pocopaw.earnings.RewardCaptureContext
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.planning.DefaultFillerRotationController
import com.atombits.pocopaw.earnings.planning.FillerRotationController
import com.atombits.pocopaw.earnings.rewards.DefaultRewardLedgerCenter
import com.atombits.pocopaw.earnings.rewards.RewardCapturePipeline
import com.atombits.pocopaw.earnings.rewards.RewardLedgerCenter
import com.atombits.pocopaw.earnings.rewards.UnobservedRewardCapturePipeline
import com.atombits.pocopaw.earnings.withEarningsHubState
import java.util.UUID

interface EarningsExecutionLane {
    suspend fun tick(store: PrototypeStoreData, now: Long): EarningsLaneTickResult
    suspend fun dispatchImportant(store: PrototypeStoreData, occurrenceId: String): DispatchResult
    suspend fun dispatchFiller(store: PrototypeStoreData, taskKey: String): DispatchResult
    fun requestCancel(store: PrototypeStoreData, reason: String, now: Long): PrototypeStoreData
    suspend fun resume(store: PrototypeStoreData, now: Long): EarningsLaneTickResult
}

class DefaultEarningsExecutionLane(
    private val executionBridge: EarningsExecutionBridge = UnwiredEarningsExecutionBridge,
    private val ledgerCenter: ExecutionLedgerCenter = DefaultExecutionLedgerCenter,
    private val rewardLedgerCenter: RewardLedgerCenter = DefaultRewardLedgerCenter,
    private val rewardCapturePipeline: RewardCapturePipeline = UnobservedRewardCapturePipeline,
    private val sweeper: ImportantOccurrenceSweeper = DefaultImportantOccurrenceSweeper(ledgerCenter),
    private val wakeScheduler: EarningsWakeScheduler = DefaultEarningsWakeScheduler,
    private val fillerRotationController: FillerRotationController = DefaultFillerRotationController,
    private val fillerPostRunCooldownObserver: FillerPostRunCooldownObserver = ExecutionEventFillerPostRunCooldownObserver
) : EarningsExecutionLane {
    override suspend fun tick(store: PrototypeStoreData, now: Long): EarningsLaneTickResult {
        val swept = sweeper.sweep(store, now).updatedStore
        val dueUpdated = markDueOccurrencesReady(swept, now)
        val hub = dueUpdated.earningsHubOrDefault(now)
        if (!hub.enabled) {
            val idleStore = dueUpdated.withEarningsHubState(
                hub.copy(executionLaneState = hub.executionLaneState.copy(laneStatus = EarningsLaneStatus.IDLE, nextWakeAt = null))
            )
            return EarningsLaneTickResult(updatedStore = idleStore, summary = "earnings automation disabled")
        }
        if (hub.executionLaneState.laneStatus in setOf(EarningsLaneStatus.RUNNING_IMPORTANT, EarningsLaneStatus.RUNNING_FILLER, EarningsLaneStatus.CAPTURING_REWARD)) {
            return EarningsLaneTickResult(updatedStore = dueUpdated, nextWakeAt = hub.executionLaneState.nextWakeAt, summary = "earnings lane already running")
        }
        val readyImportant = hub.planningState.importantScheduleQueue
            .filter { occurrence -> occurrence.status == ImportantOccurrenceStatus.READY }
            .sortedWith(compareByDescending<ImportantOccurrence> { occurrence -> occurrence.finalScore }.thenBy { occurrence -> occurrence.plannedRunAt })
            .firstOrNull()
        if (readyImportant != null) {
            val dispatch = dispatchImportant(dueUpdated, readyImportant.occurrenceId)
            return EarningsLaneTickResult(
                updatedStore = dispatch.updatedStore,
                dispatchedExecutionId = dispatch.executionId,
                nextWakeAt = wakeScheduler.resolveNextWakeAt(dispatch.updatedStore, now),
                summary = dispatch.failureReason ?: "dispatched important earnings occurrence"
            )
        }
        val filler = fillerRotationController.chooseNextFiller(hub.planningState, hub.rewardLedgerState, now)
        if (filler != null) {
            val dispatch = dispatchFiller(dueUpdated, filler.taskKey)
            return EarningsLaneTickResult(
                updatedStore = dispatch.updatedStore,
                dispatchedExecutionId = dispatch.executionId,
                nextWakeAt = wakeScheduler.resolveNextWakeAt(dispatch.updatedStore, now),
                summary = dispatch.failureReason ?: "dispatched filler earnings task"
            )
        }
        val nextWake = wakeScheduler.resolveNextWakeAt(dueUpdated, now)
        val waitingStore = dueUpdated.withEarningsHubState(
            hub.copy(
                executionLaneState = hub.executionLaneState.copy(
                    laneStatus = if (nextWake == null) EarningsLaneStatus.IDLE else EarningsLaneStatus.WAITING_IMPORTANT_WINDOW,
                    nextWakeAt = nextWake
                )
            )
        )
        return EarningsLaneTickResult(updatedStore = waitingStore, nextWakeAt = nextWake, summary = "no earnings task ready")
    }

    override suspend fun dispatchImportant(store: PrototypeStoreData, occurrenceId: String): DispatchResult {
        val now = System.currentTimeMillis()
        val hub = store.earningsHubOrDefault(now)
        val occurrence = hub.planningState.importantScheduleQueue.firstOrNull { item -> item.occurrenceId == occurrenceId }
            ?: return DispatchResult(store, failureReason = "important occurrence not found")
        val executionId = UUID.randomUUID().toString()
        val runningStore = updateImportantStatus(store, occurrenceId, ImportantOccurrenceStatus.RUNNING, now).withEarningsLane(
            EarningsExecutionLaneState(
                laneStatus = EarningsLaneStatus.RUNNING_IMPORTANT,
                activeExecutionId = executionId,
                activeOccurrenceId = occurrence.occurrenceId,
                activeTaskKey = occurrence.taskKey,
                activeAppId = occurrence.appId,
                activeExecutionKind = occurrence.category,
                lastDispatchSummary = "important:${occurrence.displayName}"
            ),
            now
        )
        val startedStore = ledgerCenter.append(runningStore, occurrence.toStartedLedgerEntry(executionId, now))
        val rewardBaseline = rewardCapturePipeline.captureBeforeExecution(startedStore, occurrence.appId, occurrence.taskKey, now)
        val dispatch = executionBridge.startExecution(startedStore, occurrence.toDispatchRequest(executionId), now)
        if (dispatch.started) {
            return if (dispatch.completed && dispatch.terminalStatus != null) {
                finalizeImportantDispatch(dispatch, occurrence, executionId, now, rewardBaseline)
            } else {
                dispatch
            }
        }
        val failureReason = dispatch.failureReason ?: "execution bridge did not start"
        val failedStore = ledgerCenter.append(
            dispatch.updatedStore,
            occurrence.toTerminalLedgerEntry(
                executionId = executionId,
                startedAt = now,
                finishedAt = now,
                status = ExecutionLedgerStatus.FAILED,
                reason = failureReason
            )
        ).let { updated -> updateImportantStatus(updated, occurrenceId, ImportantOccurrenceStatus.FAILED, now) }
            .withEarningsLane(
                startedStore.earningsHubOrDefault(now).executionLaneState.copy(
                    laneStatus = EarningsLaneStatus.BLOCKED,
                    blockReason = failureReason,
                    nextWakeAt = wakeScheduler.resolveNextWakeAt(startedStore, now)
                ),
                now
            )
        return dispatch.copy(updatedStore = failedStore, failureReason = failureReason)
    }

    override suspend fun dispatchFiller(store: PrototypeStoreData, taskKey: String): DispatchResult {
        val now = System.currentTimeMillis()
        val hub = store.earningsHubOrDefault(now)
        val candidate = hub.planningState.fillerCandidatePool.firstOrNull { filler -> filler.taskKey == taskKey }
            ?: return DispatchResult(store, failureReason = "filler candidate not found")
        val executionId = UUID.randomUUID().toString()
        val laneState = EarningsExecutionLaneState(
            laneStatus = EarningsLaneStatus.RUNNING_FILLER,
            activeExecutionId = executionId,
            activeTaskKey = candidate.taskKey,
            activeAppId = candidate.appId,
            activeExecutionKind = TaskCategory.FILLER_REPEATABLE_DECAY,
            lastDispatchSummary = "filler:${candidate.displayName}"
        )
        val runningStore = store.withEarningsLane(laneState, now)
        val startedStore = ledgerCenter.append(
            runningStore,
            ExecutionLedgerEntry(
                executionId = executionId,
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                category = TaskCategory.FILLER_REPEATABLE_DECAY,
                startedAt = now,
                status = ExecutionLedgerStatus.STARTED
            )
        )
        val rewardBaseline = rewardCapturePipeline.captureBeforeExecution(startedStore, candidate.appId, candidate.taskKey, now)
        val dispatch = executionBridge.startExecution(
            startedStore,
            EarningsDispatchRequest(
                executionId = executionId,
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                category = TaskCategory.FILLER_REPEATABLE_DECAY
            ),
            now
        )
        if (dispatch.started) {
            return if (dispatch.completed && dispatch.terminalStatus != null) {
                finalizeFillerDispatch(dispatch, candidate, executionId, now, rewardBaseline)
            } else {
                dispatch
            }
        }
        val failureReason = dispatch.failureReason ?: "execution bridge did not start"
        val failedStore = ledgerCenter.append(
            dispatch.updatedStore,
            ExecutionLedgerEntry(
                executionId = executionId,
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                category = TaskCategory.FILLER_REPEATABLE_DECAY,
                startedAt = now,
                finishedAt = now,
                status = ExecutionLedgerStatus.FAILED,
                terminalReason = failureReason
            )
        ).withEarningsLane(
            laneState.copy(
                laneStatus = EarningsLaneStatus.BLOCKED,
                blockReason = failureReason,
                nextWakeAt = wakeScheduler.resolveNextWakeAt(dispatch.updatedStore, now)
            ),
            now
        )
        return dispatch.copy(updatedStore = failedStore, failureReason = failureReason)
    }

    override fun requestCancel(store: PrototypeStoreData, reason: String, now: Long): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(now)
        val cancelledQueue = hub.planningState.importantScheduleQueue.map { occurrence ->
            if (occurrence.status == ImportantOccurrenceStatus.RUNNING && occurrence.occurrenceId == hub.executionLaneState.activeOccurrenceId) {
                occurrence.copy(status = ImportantOccurrenceStatus.CANCELLED, lastEvaluatedAt = now, skipReason = reason)
            } else {
                occurrence
            }
        }
        return store.withEarningsHubState(
            hub.copy(
                planningState = hub.planningState.copy(importantScheduleQueue = cancelledQueue),
                executionLaneState = EarningsExecutionLaneState(
                    laneStatus = EarningsLaneStatus.CANCELLED,
                    cancelRequestedAt = now,
                    lastDispatchSummary = reason
                )
            )
        )
    }

    override suspend fun resume(store: PrototypeStoreData, now: Long): EarningsLaneTickResult {
        val hub = store.earningsHubOrDefault(now)
        val resumedStore = store.withEarningsHubState(
            hub.copy(
                executionLaneState = EarningsExecutionLaneState(
                    laneStatus = EarningsLaneStatus.WAITING_IMPORTANT_WINDOW,
                    nextWakeAt = wakeScheduler.resolveNextWakeAt(store, now)
                )
            )
        )
        return tick(resumedStore, now)
    }

    private fun markDueOccurrencesReady(store: PrototypeStoreData, now: Long): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(now)
        val updatedQueue = hub.planningState.importantScheduleQueue.map { occurrence ->
            if (occurrence.status == ImportantOccurrenceStatus.QUEUED && occurrence.plannedRunAt <= now && now <= occurrence.plannedLatestAt) {
                occurrence.copy(status = ImportantOccurrenceStatus.READY, lastEvaluatedAt = now)
            } else {
                occurrence
            }
        }
        return store.withEarningsHubState(hub.copy(planningState = hub.planningState.copy(importantScheduleQueue = updatedQueue)))
    }

    private fun updateImportantStatus(
        store: PrototypeStoreData,
        occurrenceId: String,
        status: ImportantOccurrenceStatus,
        now: Long
    ): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(now)
        val updatedQueue = hub.planningState.importantScheduleQueue.map { occurrence ->
            if (occurrence.occurrenceId == occurrenceId) {
                occurrence.copy(status = status, lastEvaluatedAt = now)
            } else {
                occurrence
            }
        }
        return store.withEarningsHubState(hub.copy(planningState = hub.planningState.copy(importantScheduleQueue = updatedQueue)))
    }

    private fun PrototypeStoreData.withEarningsLane(laneState: EarningsExecutionLaneState, now: Long): PrototypeStoreData {
        val hub = earningsHubOrDefault(now)
        return withEarningsHubState(hub.copy(executionLaneState = laneState))
    }

    private fun ImportantOccurrence.toStartedLedgerEntry(executionId: String, now: Long): ExecutionLedgerEntry {
        return ExecutionLedgerEntry(
            executionId = executionId,
            occurrenceId = occurrenceId,
            appId = appId,
            taskKey = taskKey,
            displayName = displayName,
            category = category,
            windowLabel = windowLabel,
            plannedWindowId = plannedWindowId,
            startedAt = now,
            status = ExecutionLedgerStatus.STARTED
        )
    }

    private fun ImportantOccurrence.toTerminalLedgerEntry(
        executionId: String,
        startedAt: Long,
        finishedAt: Long,
        status: ExecutionLedgerStatus,
        reason: String?
    ): ExecutionLedgerEntry {
        return ExecutionLedgerEntry(
            executionId = executionId,
            occurrenceId = occurrenceId,
            appId = appId,
            taskKey = taskKey,
            displayName = displayName,
            category = category,
            windowLabel = windowLabel,
            plannedWindowId = plannedWindowId,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status,
            terminalReason = reason,
            permanentCompletion = category == TaskCategory.ONE_TIME && status == ExecutionLedgerStatus.COMPLETED
        )
    }

    private fun ImportantOccurrence.toDispatchRequest(executionId: String): EarningsDispatchRequest {
        return EarningsDispatchRequest(
            executionId = executionId,
            occurrenceId = occurrenceId,
            appId = appId,
            taskKey = taskKey,
            displayName = displayName,
            category = category,
            windowLabel = windowLabel,
            plannedWindowId = plannedWindowId
        )
    }

    private suspend fun finalizeImportantDispatch(
        dispatch: DispatchResult,
        occurrence: ImportantOccurrence,
        executionId: String,
        startedAt: Long,
        rewardBaseline: RewardBaseline
    ): DispatchResult {
        val finishedAt = dispatch.finishedAt ?: System.currentTimeMillis()
        val terminalStatus = dispatch.terminalStatus ?: ExecutionLedgerStatus.FAILED
        val terminalReason = dispatch.failureReason ?: dispatch.terminalSummary ?: terminalStatus.name
        val terminalStore = ledgerCenter.append(
            dispatch.updatedStore,
            occurrence.toTerminalLedgerEntry(
                executionId = executionId,
                startedAt = startedAt,
                finishedAt = finishedAt,
                status = terminalStatus,
                reason = terminalReason
            )
        )
        val rewardResult = rewardCapturePipeline.captureAfterExecution(
            terminalStore,
            rewardBaseline,
            RewardCaptureContext(
                executionId = executionId,
                occurrenceId = occurrence.occurrenceId,
                appId = occurrence.appId,
                taskKey = occurrence.taskKey,
                displayName = occurrence.displayName,
                category = occurrence.category,
                windowLabel = occurrence.windowLabel,
                plannedWindowId = occurrence.plannedWindowId,
                startedAt = startedAt
            ),
            finishedAt
        )
        val rewardStore = rewardLedgerCenter.append(terminalStore, rewardResult.ledgerEntry)
        val status = if (terminalStatus == ExecutionLedgerStatus.COMPLETED) {
            ImportantOccurrenceStatus.SUCCEEDED
        } else {
            ImportantOccurrenceStatus.FAILED
        }
        val statusStore = updateImportantStatus(rewardStore, occurrence.occurrenceId, status, finishedAt)
        val nextWake = wakeScheduler.resolveNextWakeAt(statusStore, finishedAt)
        val finalLane = if (terminalStatus == ExecutionLedgerStatus.FAILED) {
            EarningsExecutionLaneState(
                laneStatus = EarningsLaneStatus.BLOCKED,
                blockReason = terminalReason,
                nextWakeAt = nextWake,
                lastDispatchSummary = terminalReason
            )
        } else {
            EarningsExecutionLaneState(
                laneStatus = if (nextWake == null) EarningsLaneStatus.IDLE else EarningsLaneStatus.WAITING_IMPORTANT_WINDOW,
                nextWakeAt = nextWake,
                lastDispatchSummary = rewardResult.summary ?: terminalReason
            )
        }
        val finalStore = statusStore.withEarningsLane(finalLane, finishedAt)
        return dispatch.copy(
            updatedStore = finalStore,
            failureReason = if (terminalStatus == ExecutionLedgerStatus.FAILED) terminalReason else null
        )
    }

    private suspend fun finalizeFillerDispatch(
        dispatch: DispatchResult,
        candidate: FillerCandidateRecord,
        executionId: String,
        startedAt: Long,
        rewardBaseline: RewardBaseline
    ): DispatchResult {
        val finishedAt = dispatch.finishedAt ?: System.currentTimeMillis()
        val initialTerminalStatus = dispatch.terminalStatus ?: ExecutionLedgerStatus.FAILED
        val initialTerminalReason = dispatch.failureReason ?: dispatch.terminalSummary ?: initialTerminalStatus.name
        val preliminaryCooldownObservations = if (initialTerminalStatus == ExecutionLedgerStatus.FAILED && initialTerminalReason.isCooldownRecoverableFillerFailure()) {
            fillerPostRunCooldownObserver.observe(
                store = dispatch.updatedStore,
                candidate = candidate,
                executionId = executionId,
                terminalReason = initialTerminalReason,
                finishedAt = finishedAt
            )
        } else {
            emptyList()
        }
        val completedByCooldownEvidence = shouldCompleteFailedFillerFromCooldownEvidence(
            terminalStatus = initialTerminalStatus,
            terminalReason = initialTerminalReason,
            candidate = candidate,
            cooldownObservations = preliminaryCooldownObservations
        )
        val terminalStatus = if (completedByCooldownEvidence) {
            ExecutionLedgerStatus.COMPLETED
        } else {
            initialTerminalStatus
        }
        val terminalReason = if (completedByCooldownEvidence) {
            buildCooldownCompletedTerminalReason(candidate, preliminaryCooldownObservations)
        } else {
            initialTerminalReason
        }
        val terminalStore = ledgerCenter.append(
            dispatch.updatedStore,
            ExecutionLedgerEntry(
                executionId = executionId,
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                category = TaskCategory.FILLER_REPEATABLE_DECAY,
                startedAt = startedAt,
                finishedAt = finishedAt,
                status = terminalStatus,
                terminalReason = terminalReason
            )
        )
        val rewardResult = rewardCapturePipeline.captureAfterExecution(
            terminalStore,
            rewardBaseline,
            RewardCaptureContext(
                executionId = executionId,
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                category = TaskCategory.FILLER_REPEATABLE_DECAY,
                startedAt = startedAt
            ),
            finishedAt
        )
        val rewardStore = rewardLedgerCenter.append(terminalStore, rewardResult.ledgerEntry)
        val reconciliationAt = rewardResult.ledgerEntry.finishedAt
        val postRewardCooldownObservations = fillerPostRunCooldownObserver.observe(
            store = rewardStore,
            candidate = candidate,
            executionId = executionId,
            terminalReason = terminalReason,
            finishedAt = reconciliationAt
        )
        val cooldownObservations = preliminaryCooldownObservations + postRewardCooldownObservations
        val reconciledStore = rewardStore.reconcileFillerCandidateAfterTerminal(
            candidate = candidate,
            cooldownObservations = cooldownObservations,
            finishedAt = reconciliationAt
        )
        val nextWake = wakeScheduler.resolveNextWakeAt(reconciledStore, reconciliationAt)
        val finalLane = if (terminalStatus == ExecutionLedgerStatus.FAILED) {
            EarningsExecutionLaneState(
                laneStatus = EarningsLaneStatus.BLOCKED,
                blockReason = terminalReason,
                nextWakeAt = nextWake,
                lastDispatchSummary = terminalReason
            )
        } else {
            EarningsExecutionLaneState(
                laneStatus = if (nextWake == null) EarningsLaneStatus.IDLE else EarningsLaneStatus.WAITING_IMPORTANT_WINDOW,
                nextWakeAt = nextWake,
                lastDispatchSummary = rewardResult.summary ?: terminalReason
            )
        }
        val finalStore = reconciledStore.withEarningsLane(finalLane, reconciliationAt)
        return dispatch.copy(
            updatedStore = finalStore,
            completed = true,
            terminalStatus = terminalStatus,
            terminalSummary = terminalReason,
            finishedAt = reconciliationAt,
            failureReason = if (terminalStatus == ExecutionLedgerStatus.FAILED) terminalReason else null
        )
    }

    private fun shouldCompleteFailedFillerFromCooldownEvidence(
        terminalStatus: ExecutionLedgerStatus,
        terminalReason: String,
        candidate: FillerCandidateRecord,
        cooldownObservations: List<FillerCooldownObservation>
    ): Boolean {
        if (terminalStatus != ExecutionLedgerStatus.FAILED || !terminalReason.isCooldownRecoverableFillerFailure()) {
            return false
        }
        return cooldownObservations.any { observation -> observation.matchesCandidate(candidate) }
    }

    private fun String.isCooldownRecoverableFillerFailure(): Boolean {
        val normalized = lowercase()
        return normalized.contains("max step") ||
            normalized.contains("without a terminal outcome") ||
            normalized.contains("did not return a terminal") ||
            normalized.contains("missing explicit terminal") ||
            normalized.contains("automation response parse failed") ||
            normalized.contains("response parse failed")
    }

    private fun buildCooldownCompletedTerminalReason(
        candidate: FillerCandidateRecord,
        cooldownObservations: List<FillerCooldownObservation>
    ): String {
        val evidence = cooldownObservations
            .firstOrNull { observation -> observation.matchesCandidate(candidate) }
            ?.let { observation ->
                listOfNotNull(
                    observation.source.name,
                    observation.evidenceSummary?.takeIf { value -> value.isNotBlank() }
                ).joinToString(": ")
            }
            .orEmpty()
        return listOf(
            "Filler run completed by matching post-run cooldown evidence",
            evidence
        ).filter { value -> value.isNotBlank() }.joinToString("; ").take(360)
    }

    private fun PrototypeStoreData.reconcileFillerCandidateAfterTerminal(
        candidate: FillerCandidateRecord,
        cooldownObservations: List<FillerCooldownObservation>,
        finishedAt: Long
    ): PrototypeStoreData {
        val hub = earningsHubOrDefault(finishedAt)
        val targetDisplayName = candidate.displayName.normalizedFillerDisplayName()
        var changed = false
        val updatedPool = hub.planningState.fillerCandidatePool.map { record ->
            val sameCandidate = record.appId == candidate.appId && (
                record.taskKey == candidate.taskKey ||
                    (targetDisplayName.isNotBlank() && record.displayName.normalizedFillerDisplayName() == targetDisplayName)
                )
            if (!sameCandidate) {
                record
            } else {
                changed = true
                val updatedNextEligibleAt = selectCooldownUntilForRecord(record, cooldownObservations)
                    ?: record.nextEligibleAt
                record.copy(
                    nextEligibleAt = updatedNextEligibleAt,
                    lastRunFinishedAt = finishedAt
                )
            }
        }
        if (!changed) {
            return this
        }
        val updatedPlanningState = hub.planningState.copy(
            fillerCandidatePool = updatedPool,
            nextFillerEligibleAt = updatedPool.mapNotNull { record -> record.nextEligibleAt }.minOrNull()
        )
        return withEarningsHubState(hub.copy(planningState = updatedPlanningState))
    }

    private fun selectCooldownUntilForRecord(
        record: FillerCandidateRecord,
        cooldownObservations: List<FillerCooldownObservation>
    ): Long? {
        val matchingObservations = cooldownObservations.filter { observation -> observation.matchesCandidate(record) }
        val bestPriority = matchingObservations.maxOfOrNull { observation -> observation.source.priority } ?: return null
        return matchingObservations
            .filter { observation -> observation.source.priority == bestPriority }
            .map { observation -> observation.cooldownUntil }
            .minOrNull()
    }
}
