package com.atombits.pocopaw

data class SafetyDecision(
    val allowedLevel: String,
    val decisionType: String,
    val downgradeReason: String?,
    val needsHumanConfirm: Boolean,
    val confirmationReason: String?,
    val auditMessage: String
) {
    fun toPromptSection(): String {
        return buildString {
            append("allowed_level=$allowedLevel")
            append(" | decision_type=$decisionType")
            if (!downgradeReason.isNullOrBlank()) {
                append(" | downgrade_reason=$downgradeReason")
            }
            append(" | needs_human_confirm=$needsHumanConfirm")
            if (!confirmationReason.isNullOrBlank()) {
                append(" | confirmation_reason=$confirmationReason")
            }
            append(" | audit=$auditMessage")
        }
    }
}

data class SafetyBoundaryContext(
    val workflowLane: WorkflowLane = WorkflowLane.PASSIVE,
    val proactiveDeliveryPlan: ProactiveDeliveryPlan? = null,
    val personalizationPolicyBundle: PersonalizationPolicyBundle? = null
)

object SafetyBoundaryEngine {
    fun assess(
        executionBoundaryPacket: TaskExecutionBoundaryPacket?,
        toolCapabilityBundle: ToolCapabilityBundle? = null,
        context: SafetyBoundaryContext = SafetyBoundaryContext()
    ): SafetyDecision? {
        if (!RuntimeModuleSwitches.safetyBoundaryEnabled) {
            return null
        }

        if (context.workflowLane == WorkflowLane.PROACTIVE) {
            val proactiveSignal = context.proactiveDeliveryPlan?.signal
            val proactiveTolerance = context.personalizationPolicyBundle
                ?.proactiveDeliveryPolicy
                ?.proactiveTolerance
                ?.uppercase()
            if (
                proactiveTolerance == "LOW" &&
                (proactiveSignal == ProactiveOpportunitySignal.REQUEST_PROACTIVE_CONFIRM ||
                    proactiveSignal == ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING)
            ) {
                return SafetyDecision(
                    allowedLevel = "hint",
                    decisionType = "proactive_policy_hint_only",
                    downgradeReason = "personalization_proactive_tolerance_low",
                    needsHumanConfirm = false,
                    confirmationReason = null,
                    auditMessage = "personalization_policy_caps_proactive_signal_to_hint"
                )
            }
            if (proactiveSignal == ProactiveOpportunitySignal.REQUEST_PROACTIVE_CONFIRM ||
                proactiveSignal == ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING
            ) {
                return SafetyDecision(
                    allowedLevel = "confirm",
                    decisionType = "proactive_confirm_required",
                    downgradeReason = if (proactiveSignal == ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING) {
                        "proactive_execution_requires_authorization"
                    } else {
                        null
                    },
                    needsHumanConfirm = true,
                    confirmationReason = "proactive_authorization_boundary",
                    auditMessage = "lane_aware_proactive_confirmation_gate"
                )
            }
        }

        val maxToolRisk = toolCapabilityBundle?.capabilities
            ?.maxByOrNull { capability -> toolRiskPriority(capability.risk) }
            ?.risk

        if (maxToolRisk == ToolRisk.RESTRICTED) {
            return SafetyDecision(
                allowedLevel = "hint",
                decisionType = "restricted_tool_requires_confirm",
                downgradeReason = "restricted_tool_risk",
                needsHumanConfirm = true,
                confirmationReason = "restricted_tool_capability",
                auditMessage = "restricted_tool_detected_in_tool_bundle"
            )
        }

        if (maxToolRisk == ToolRisk.SENSITIVE) {
            return SafetyDecision(
                allowedLevel = "hint",
                decisionType = if (executionBoundaryPacket?.canStartExecution == true) {
                    "sensitive_tool_ready_advisory"
                } else {
                    "sensitive_tool_advisory"
                },
                downgradeReason = "sensitive_tool_risk",
                needsHumanConfirm = false,
                confirmationReason = null,
                auditMessage = if (executionBoundaryPacket == null) {
                    "sensitive_tool_detected_without_execution_boundary_packet"
                } else {
                    "sensitive_tool_detected_with_execution_boundary_packet"
                }
            )
        }

        if (executionBoundaryPacket == null) {
            return null
        }

        if (executionBoundaryPacket.canStartExecution) {
            return SafetyDecision(
                allowedLevel = "hint",
                decisionType = "ready_to_start_advisory",
                downgradeReason = null,
                needsHumanConfirm = false,
                confirmationReason = null,
                auditMessage = "task_execution_boundary_ready_to_start"
            )
        }

        return SafetyDecision(
            allowedLevel = "hint",
            decisionType = "boundary_only",
            downgradeReason = "execution_not_ready",
            needsHumanConfirm = false,
            confirmationReason = null,
            auditMessage = "execution_boundary_packet_present_but_not_ready"
        )
    }

    private fun toolRiskPriority(risk: ToolRisk): Int = when (risk) {
        ToolRisk.SAFE -> 0
        ToolRisk.SENSITIVE -> 1
        ToolRisk.RESTRICTED -> 2
    }
}