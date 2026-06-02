package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.earnings.EarningsPlanningState
import com.atombits.pocopaw.earnings.EarningsPolicyMode
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.FillerCandidateRecord
import com.atombits.pocopaw.earnings.FillerRotationPolicy
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.TaskOpportunity

interface FillerRotationController {
    fun buildPolicy(
        opportunities: List<TaskOpportunity>,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long,
        planAdvice: EarningsPlanAdvice? = null
    ): FillerRotationPolicy

    fun chooseNextFiller(
        planningState: EarningsPlanningState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): FillerCandidateRecord?
}

object DefaultFillerRotationController : FillerRotationController {
    override fun buildPolicy(
        opportunities: List<TaskOpportunity>,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long,
        planAdvice: EarningsPlanAdvice?
    ): FillerRotationPolicy {
        val locallyEligibleApps = EntertainmentAppId.defaultOrder().filter { appId ->
            opportunities.any { opportunity -> opportunity.appId == appId && opportunity.category == TaskCategory.FILLER_REPEATABLE_DECAY }
        }
        val advisedApps = planAdvice?.fillerAdvice?.candidateAppOrder.orEmpty()
            .filter { appId -> appId in locallyEligibleApps }
            .distinct()
        val candidateApps = (advisedApps + locallyEligibleApps).distinct()
        val policyMode = if (
            planAdvice?.fillerAdvice?.policyMode == EarningsPolicyMode.DYNAMIC_REWARD_AWARE &&
            rewardLedgerState.recentFillerRewardVelocityByApp.isNotEmpty()
        ) {
            EarningsPolicyMode.DYNAMIC_REWARD_AWARE
        } else {
            EarningsPolicyMode.STATIC_ROUND_ROBIN
        }
        return FillerRotationPolicy(
            policyMode = policyMode,
            candidateApps = candidateApps,
            dynamicWeightByApp = rewardLedgerState.recentFillerRewardVelocityByApp,
            lastPolicyUpdateAt = now
        )
    }

    override fun chooseNextFiller(
        planningState: EarningsPlanningState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): FillerCandidateRecord? {
        val policy = planningState.fillerRotationPolicy
        val ready = planningState.fillerCandidatePool.filter { candidate ->
            candidate.nextEligibleAt == null || candidate.nextEligibleAt <= now
        }
        if (ready.isEmpty()) {
            return null
        }
        val orderedApps = policy.candidateApps.ifEmpty { EntertainmentAppId.defaultOrder() }
        val lastChosenIndex = policy.lastChosenApp?.let(orderedApps::indexOf)?.takeIf { index -> index >= 0 } ?: -1
        val rotatedApps = orderedApps.drop(lastChosenIndex + 1) + orderedApps.take(lastChosenIndex + 1)
        return rotatedApps.asSequence()
            .mapNotNull { appId -> ready.filter { candidate -> candidate.appId == appId }.maxByOrNull(::fillerScore) }
            .firstOrNull()
            ?: ready.maxByOrNull(::fillerScore)
    }

    private fun fillerScore(candidate: FillerCandidateRecord): Double {
        val rewardScore = candidate.recentRewardPerMinute ?: 0.0
        val decayPenalty = candidate.decayLevel * 25.0
        val failurePenalty = (candidate.recentFailureRate ?: 0.0) * 200.0
        val consecutivePenalty = candidate.consecutiveRunsOnSameApp * 30.0
        return 300.0 + rewardScore - decayPenalty - failurePenalty - consecutivePenalty
    }
}
