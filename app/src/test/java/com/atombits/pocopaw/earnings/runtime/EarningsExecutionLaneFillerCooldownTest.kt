package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.ExecutionEvent
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.DispatchResult
import com.atombits.pocopaw.earnings.EarningsDispatchRequest
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.EarningsLaneStatus
import com.atombits.pocopaw.earnings.EarningsPlanningState
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.FillerCandidateRecord
import com.atombits.pocopaw.earnings.FillerRotationPolicy
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.planning.DefaultFillerRotationController
import com.atombits.pocopaw.earnings.withEarningsHubState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsExecutionLaneFillerCooldownTest {

    @Test
    fun dispatchFiller_appliesObservedCooldownToSameNamedFillerCandidates() = runBlocking {
        val initialEligibleAt = 1_780_388_095_053L
        val store = storeWithFillerCandidates(
            FillerCandidateRecord(
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:engagement:treasure_chest",
                displayName = "开宝箱得金币",
                nextEligibleAt = initialEligibleAt
            ),
            FillerCandidateRecord(
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:filler_repeatable_decay:treasure_chest",
                displayName = "开宝箱得金币",
                nextEligibleAt = initialEligibleAt
            )
        )
        val lane = DefaultEarningsExecutionLane(
            executionBridge = CompletingBridge(
                terminalSummary = "Treasure chest engagement completed. Reward claimed. Next claim available in 14m 38s."
            )
        )

        val result = lane.dispatchFiller(store, "douyin_lite:engagement:treasure_chest")
        val hub = result.updatedStore.earningsHubOrDefault()
        val terminalEntry = hub.executionLedgerState.entries.last { entry ->
            entry.status == ExecutionLedgerStatus.COMPLETED && entry.taskKey == "douyin_lite:engagement:treasure_chest"
        }
        val expectedNextEligibleAt = requireNotNull(terminalEntry.finishedAt) + ((14L * 60L + 38L) * 1_000L)

        assertEquals(expectedNextEligibleAt, hub.planningState.nextFillerEligibleAt)
        hub.planningState.fillerCandidatePool.forEach { candidate ->
            assertEquals(expectedNextEligibleAt, candidate.nextEligibleAt)
            assertEquals(terminalEntry.finishedAt, candidate.lastRunFinishedAt)
        }
        assertNull(
            DefaultFillerRotationController.chooseNextFiller(
                planningState = hub.planningState,
                rewardLedgerState = hub.rewardLedgerState,
                now = requireNotNull(terminalEntry.finishedAt) + 1_000L
            )
        )
    }

    @Test
    fun dispatchFiller_readsCooldownFromAutomationPayloadWhenTerminalSummaryIsGeneric() = runBlocking {
        val initialEligibleAt = 1_780_388_095_053L
        val store = storeWithFillerCandidates(
            FillerCandidateRecord(
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:engagement:treasure_chest",
                displayName = "开宝箱得金币",
                nextEligibleAt = initialEligibleAt
            )
        )
        val lane = DefaultEarningsExecutionLane(
            executionBridge = CompletingBridge(
                terminalSummary = "Treasure chest engagement confirmed. Current screen shows the treasure chest icon on cooldown.",
                automationPayload = "当前截图显示宝箱右下角倒计时为 11分01秒后领，说明宝箱正在冷却。"
            )
        )

        val result = lane.dispatchFiller(store, "douyin_lite:engagement:treasure_chest")
        val hub = result.updatedStore.earningsHubOrDefault()
        val terminalEntry = hub.executionLedgerState.entries.last { entry -> entry.status == ExecutionLedgerStatus.COMPLETED }
        val expectedNextEligibleAt = requireNotNull(terminalEntry.finishedAt) + ((11L * 60L + 1L) * 1_000L)

        assertEquals(expectedNextEligibleAt, hub.planningState.fillerCandidatePool.single().nextEligibleAt)
        assertTrue(requireNotNull(hub.executionLaneState.nextWakeAt) >= expectedNextEligibleAt)
    }

    @Test
    fun dispatchFiller_completesMissingTerminalFailureWhenPostRunPageCountdownMatchesCandidate() = runBlocking {
        val initialEligibleAt = 1_780_388_095_053L
        val store = storeWithFillerCandidates(
            FillerCandidateRecord(
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:filler_repeatable:watch_ads_interval",
                displayName = "看广告赚金币",
                nextEligibleAt = initialEligibleAt
            )
        )
        val lane = DefaultEarningsExecutionLane(
            executionBridge = CompletingBridge(
                terminalSummary = "Exploratory automation reached the max step budget without a terminal outcome.",
                terminalStatus = ExecutionLedgerStatus.FAILED
            ),
            fillerPostRunCooldownObserver = PageTextCooldownObserver(
                pageText = "赚钱任务\n看广告赚金币\n18:36\n倒计时进行中"
            )
        )

        val result = lane.dispatchFiller(store, "douyin_lite:filler_repeatable:watch_ads_interval")
        val hub = result.updatedStore.earningsHubOrDefault()
        val terminalEntry = hub.executionLedgerState.entries.last { entry -> entry.status == ExecutionLedgerStatus.COMPLETED }
        val expectedNextEligibleAt = requireNotNull(terminalEntry.finishedAt) + ((18L * 60L + 36L) * 1_000L)

        assertNull(result.failureReason)
        assertEquals(EarningsLaneStatus.WAITING_IMPORTANT_WINDOW, hub.executionLaneState.laneStatus)
        assertTrue(terminalEntry.terminalReason.orEmpty().contains("post-run cooldown evidence"))
        assertEquals(expectedNextEligibleAt, hub.planningState.fillerCandidatePool.single().nextEligibleAt)
        assertEquals(terminalEntry.finishedAt, hub.planningState.fillerCandidatePool.single().lastRunFinishedAt)
    }

    @Test
    fun dispatchFiller_completesParseFailureWhenAutomationPayloadCountdownMatchesCandidate() = runBlocking {
        val initialEligibleAt = 1_780_388_095_053L
        val store = storeWithFillerCandidates(
            FillerCandidateRecord(
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:filler_repeatable_decay:ad_task_20min",
                displayName = "看广告赚金币",
                nextEligibleAt = initialEligibleAt
            )
        )
        val lane = DefaultEarningsExecutionLane(
            executionBridge = CompletingBridge(
                terminalSummary = "Automation response parse failed: JSONException: Unterminated string at character 4382.",
                automationPayload = "The target task '看广告赚金币' is visible with a countdown timer of '03:25', indicating cooldown.",
                terminalStatus = ExecutionLedgerStatus.FAILED
            )
        )

        val result = lane.dispatchFiller(store, "douyin_lite:filler_repeatable_decay:ad_task_20min")
        val hub = result.updatedStore.earningsHubOrDefault()
        val terminalEntry = hub.executionLedgerState.entries.last { entry -> entry.status == ExecutionLedgerStatus.COMPLETED }
        val expectedNextEligibleAt = requireNotNull(terminalEntry.finishedAt) + ((3L * 60L + 25L) * 1_000L)

        assertNull(result.failureReason)
        assertEquals(EarningsLaneStatus.WAITING_IMPORTANT_WINDOW, hub.executionLaneState.laneStatus)
        assertTrue(terminalEntry.terminalReason.orEmpty().contains("post-run cooldown evidence"))
        assertEquals(expectedNextEligibleAt, hub.planningState.fillerCandidatePool.single().nextEligibleAt)
    }

    @Test
    fun cooldownParser_readsColonCountdownWithoutTreatingClockWindowAsCooldown() {
        assertEquals(
            (18L * 60L + 36L) * 1_000L,
            FillerCooldownTextParser.extractCooldownDelayMs("看广告赚金币 18:36 倒计时")
        )
        assertNull(FillerCooldownTextParser.extractCooldownDelayMs("活动时间 20:00-22:00"))
    }

    private fun storeWithFillerCandidates(vararg candidates: FillerCandidateRecord): PrototypeStoreData {
        val candidateList = candidates.toList()
        return PrototypeStoreData().withEarningsHubState(
            EarningsHubState(
                enabled = true,
                planningState = EarningsPlanningState(
                    fillerCandidatePool = candidateList,
                    fillerRotationPolicy = FillerRotationPolicy(candidateApps = listOf(EntertainmentAppId.DOUYIN_LITE)),
                    nextFillerEligibleAt = candidateList.mapNotNull { candidate -> candidate.nextEligibleAt }.minOrNull()
                )
            )
        )
    }

    private class CompletingBridge(
        private val terminalSummary: String,
        private val automationPayload: String? = null,
        private val terminalStatus: ExecutionLedgerStatus = ExecutionLedgerStatus.COMPLETED
    ) : EarningsExecutionBridge {
        override suspend fun startExecution(
            store: PrototypeStoreData,
            dispatch: EarningsDispatchRequest,
            now: Long
        ): DispatchResult {
            val eventStore = automationPayload?.let { payload ->
                store.copy(
                    executionEvents = (store.executionEvents + ExecutionEvent(
                        candidateId = null,
                        summary = terminalSummary,
                        keyInfo = "task=earnings:${dispatch.executionId}",
                        automationResponsePayload = payload,
                        startedAt = now
                    )).toMutableList()
                )
            } ?: store
            return DispatchResult(
                updatedStore = eventStore,
                executionId = dispatch.executionId,
                started = true,
                completed = true,
                terminalStatus = terminalStatus,
                terminalSummary = terminalSummary,
                finishedAt = now + 1_000L
            )
        }
    }

    private class PageTextCooldownObserver(
        private val pageText: String
    ) : FillerPostRunCooldownObserver {
        override suspend fun observe(
            store: PrototypeStoreData,
            candidate: FillerCandidateRecord,
            executionId: String,
            terminalReason: String,
            finishedAt: Long
        ): List<FillerCooldownObservation> {
            return listOfNotNull(
                buildFillerCooldownObservationFromPageText(
                    candidate = candidate,
                    observedAt = finishedAt,
                    pageText = pageText
                )
            )
        }
    }
}