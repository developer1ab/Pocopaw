package com.atombits.pocopaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class VisionExecutionFlowTest {

    @Test
    fun settingsDefaults_matchRequestedValues() {
        assertFalse(DEFAULT_VISION_REQUEST_THINKING_ENABLED)
        assertEquals(2, DEFAULT_CAPTURE_COMPRESSION_SCALE)
        assertEquals(1, MIN_CAPTURE_COMPRESSION_SCALE)
    }

    @Test
    fun applyVisionRequestTuning_appliesCompatibilityFlagsForSupportedProviders() {
        VisionRequestThinkingRuntime.setEnabled(true)
        VisionRequestSearchRuntime.setEnabled(true)

        val payload = JSONObject().applyVisionRequestTuning(VisionProviderKind.QWEN_VISION)

        assertTrue(payload.getBoolean("enable_thinking"))
        assertTrue(payload.getBoolean("enable_search"))

        VisionRequestThinkingRuntime.setEnabled(DEFAULT_VISION_REQUEST_THINKING_ENABLED)
        VisionRequestSearchRuntime.setEnabled(DEFAULT_VISION_REQUEST_SEARCH_ENABLED)
    }

    @Test
    fun applyVisionRequestTuning_skipsUnsupportedProviders() {
        VisionRequestThinkingRuntime.setEnabled(true)
        VisionRequestSearchRuntime.setEnabled(true)

        val payload = JSONObject().applyVisionRequestTuning(VisionProviderKind.OPENAI_VISION)

        assertFalse(payload.has("enable_thinking"))
        assertFalse(payload.has("enable_search"))

        VisionRequestThinkingRuntime.setEnabled(DEFAULT_VISION_REQUEST_THINKING_ENABLED)
        VisionRequestSearchRuntime.setEnabled(DEFAULT_VISION_REQUEST_SEARCH_ENABLED)
    }

    @Test
    fun captureCompressionHelpers_normalizeScaleQualityAndDimensions() {
        assertEquals(1, normalizeCaptureCompressionScale(0))
        assertEquals(80, resolveCaptureCompressionQuality(1))
        assertEquals(72, resolveCaptureCompressionQuality(2))
        assertEquals(64, resolveCaptureCompressionQuality(4))
        assertEquals(1080, resolveCaptureScaledDimension(1080, 1))
        assertEquals(540, resolveCaptureScaledDimension(1080, 2))
        assertEquals(360, resolveCaptureScaledDimension(1080, 3))
        assertEquals(1, resolveCaptureScaledDimension(1, 99))
        assertEquals("3x / JPEG 64", formatCaptureCompressionSummary(3))
    }

    @Test
    fun parseVisionGroundingResult_parsesTypedActionSignalsAndContinuation() {
        val result = parseVisionGroundingResult(
            content = """
                {
                  "resolved": true,
                  "step_type": "NAVIGATE",
                  "action_type": "tap",
                  "target_x": 0.73,
                  "target_y": 0.88,
                  "expected_outcome": "checkout visible",
                  "verification_signals": ["checkout visible", "buy button highlighted"],
                  "continuation_mode": "VERIFY_THEN_CONTINUE",
                  "fallback_policy": "VISION",
                  "risk_level": "MEDIUM",
                                    "page_signature": "checkout_confirm_v2",
                                    "locator_hint": "buy_now_button",
                  "note": "tap the primary CTA"
                }
            """.trimIndent(),
            defaultExpectedOutcome = "fallback"
        )

        assertTrue(result.resolved)
        assertEquals("NAVIGATE", result.stepType)
        assertEquals(VisionActionType.TAP, result.actionType)
        assertEquals(0.73f, result.targetX)
        assertEquals(0.88f, result.targetY)
        assertEquals(listOf("checkout visible", "buy button highlighted"), result.verificationSignals)
        assertEquals(VisionContinuationMode.VERIFY_THEN_CONTINUE, result.continuationMode)
        assertEquals("VISION", result.fallbackPolicy)
        assertEquals("MEDIUM", result.riskLevel)
        assertEquals("checkout_confirm_v2", result.pageSignature)
        assertEquals("buy_now_button", result.locatorHint)
    }

    @Test
    fun parseVisionGroundingResult_parsesInputPayloadAndMapsToBridgeAction() {
        val result = parseVisionGroundingResult(
            content = """
                {
                  "resolved": true,
                  "step_type": "TYPE_QUERY",
                  "action_type": "input",
                                    "target_x": 0.48,
                                    "target_y": 0.67,
                  "input_slot_key": "target_object",
                  "text": "hallucinated text",
                  "expected_outcome": "search_results_visible"
                }
            """.trimIndent(),
            defaultExpectedOutcome = "fallback",
            inputCandidates = mapOf(
                "target_object" to "coffee beans"
            )
        )

        assertEquals(VisionActionType.INPUT, result.actionType)
        assertEquals(0.48f, result.targetX)
        assertEquals(0.67f, result.targetY)
        assertEquals("target_object", result.inputSlotKey)
        assertEquals("coffee beans", result.inputText)
        assertEquals(
            BridgeAction(type = "inputText", x = 0.48f, y = 0.67f, text = "coffee beans"),
            toVisionBridgeAction(result)
        )
    }

    @Test
    fun parseVisionGroundingResult_rejectsBroadInputCandidateWhenSpecificCandidateExists() {
        val result = parseVisionGroundingResult(
            content = """
                {
                  "resolved": true,
                  "step_type": "TYPE_QUERY",
                  "action_type": "input",
                  "input_slot_key": "shopping.product_type",
                  "expected_outcome": "search_results_visible"
                }
            """.trimIndent(),
            defaultExpectedOutcome = "fallback",
            inputCandidates = mapOf(
                "target_object" to "Wilson Hyper Hammer 5.3 网球拍",
                "shopping.product_type" to "网球拍"
            )
        )

        assertEquals(VisionActionType.INPUT, result.actionType)
        assertEquals(null, result.inputSlotKey)
        assertEquals(null, result.inputText)
    }

    @Test
    fun parseVisionGroundingResult_parsesSwipePayloadAndMapsToBridgeAction() {
        val result = parseVisionGroundingResult(
            content = """
                {
                  "resolved": true,
                  "step_type": "SCROLL_RESULTS",
                  "action_type": "swipe",
                  "from_x": 0.52,
                  "from_y": 0.81,
                  "to_x": 0.52,
                  "to_y": 0.24,
                  "duration_ms": 420,
                  "expected_outcome": "next_results_visible"
                }
            """.trimIndent(),
            defaultExpectedOutcome = "fallback"
        )

        assertEquals(VisionActionType.SWIPE, result.actionType)
        assertEquals(0.52f, result.swipeFromX)
        assertEquals(0.81f, result.swipeFromY)
        assertEquals(0.52f, result.swipeToX)
        assertEquals(0.24f, result.swipeToY)
        assertEquals(420L, result.actionDurationMs)
        assertEquals(
            BridgeAction(
                type = "swipe",
                fromX = 0.52f,
                fromY = 0.81f,
                toX = 0.52f,
                toY = 0.24f,
                duration = 420L
            ),
            toVisionBridgeAction(result)
        )
    }

    @Test
    fun mergeVisionExecutionNote_keepsGroundingContextAndAppendsExecutionDiagnostics() {
        assertEquals(
            "type coffee beans in the search field | input | target=coordinate_descendant:android.widget.EditText | coords=(0.48,0.67) | layer2=set_text_failed | layer3=focus=click/paste/readback_blank",
            mergeVisionExecutionNote(
                groundingNote = "type coffee beans in the search field",
                executionNote = "input | target=coordinate_descendant:android.widget.EditText | coords=(0.48,0.67) | layer2=set_text_failed | layer3=focus=click/paste/readback_blank"
            )
        )
    }

    @Test
    fun parseVisionGroundingResult_defaultsMissingOptionalFieldsSafely() {
        val result = parseVisionGroundingResult(
            content = """
                {
                  "resolved": false,
                  "step_type": "VERIFY"
                }
            """.trimIndent(),
            defaultExpectedOutcome = "order_confirm_page_visible"
        )

        assertFalse(result.resolved)
        assertEquals(VisionActionType.NONE, result.actionType)
        assertEquals(emptyList<String>(), result.verificationSignals)
        assertEquals(VisionContinuationMode.STOP, result.continuationMode)
        assertEquals("order_confirm_page_visible", result.expectedOutcome)
        assertEquals("STOP", result.fallbackPolicy)
        assertEquals("LOW", result.riskLevel)
    }

    @Test
    fun parseVisionVerificationResult_parsesMatchedSignalAndReason() {
        val result = parseVisionVerificationResult(
            content = """
                {
                  "matched": true,
                  "matched_signal": "order_confirm_page_visible",
                  "note": "confirmation header is visible"
                }
            """.trimIndent(),
            defaultExpectedOutcome = "checkout_visible"
        )

        assertTrue(result.matched)
        assertEquals("order_confirm_page_visible", result.matchedSignal)
        assertEquals("confirmation header is visible", result.note)
    }

    @Test
    fun parseVisionVerificationResult_defaultsToExpectedOutcomeWhenSignalMissing() {
        val result = parseVisionVerificationResult(
            content = """
                {
                  "matched": false
                }
            """.trimIndent(),
            defaultExpectedOutcome = "order_confirm_page_visible"
        )

        assertFalse(result.matched)
        assertEquals("order_confirm_page_visible", result.matchedSignal)
    }

    @Test
    fun buildVisionGroundingPacket_includesStepContextWhenProvided() {
        val packet = PromptCenter.buildVisionGroundingPacket(
            VisionGroundingRequest(
                objective = "在京东购买5元手电筒",
                expectedOutcome = "搜索框获得焦点",
                imageDataUrl = "data:image/jpeg;base64,AAA",
                captureWidth = 1080,
                captureHeight = 2400,
                locatorHint = "search_box",
                verificationSignals = listOf("搜索框获得焦点", "键盘弹出"),
                stepNote = "点击搜索框准备输入搜索词",
                pageSignature = "jd_home_search",
                processGuidance = "stage_hint=search | why=matched_anchor=search_box@jd_home_search | caution=failure_pattern=empty_results",
                inputCandidates = mapOf(
                    "target_object" to "5元手电筒",
                    "commerce_service.product_type" to "手电筒"
                )
            )
        )

        val userPrompt = packet.promptMessages.last().content
        assertTrue(userPrompt.contains("Objective:"))
        assertTrue(userPrompt.contains("在京东购买5元手电筒"))
        assertTrue(userPrompt.contains("Expected outcome:"))
        assertTrue(userPrompt.contains("搜索框获得焦点"))
        assertTrue(userPrompt.contains("Process guidance:"))
        assertTrue(userPrompt.contains("stage_hint=search"))
        assertTrue(userPrompt.contains("Authoritative input payloads:"))
        assertTrue(userPrompt.contains("- target_object = 5元手电筒"))
        assertTrue(userPrompt.contains("Apply constraint monotonicity when choosing among payloads"))
        assertTrue(packet.responseContract.contains("input_slot_key"))
    }

    @Test
    fun unsupportedVisionActionNote_reportsMissingPayloadDetails() {
        assertEquals(
            "vision input action missing text payload",
            unsupportedVisionActionNote(
                VisionGroundingResult(
                    resolved = true,
                    stepType = "TYPE_QUERY",
                    actionType = VisionActionType.INPUT,
                    expectedOutcome = "search_results_visible"
                )
            )
        )
        assertEquals(
            "vision swipe action missing path payload",
            unsupportedVisionActionNote(
                VisionGroundingResult(
                    resolved = true,
                    stepType = "SCROLL_RESULTS",
                    actionType = VisionActionType.SWIPE,
                    expectedOutcome = "next_results_visible"
                )
            )
        )
    }
}