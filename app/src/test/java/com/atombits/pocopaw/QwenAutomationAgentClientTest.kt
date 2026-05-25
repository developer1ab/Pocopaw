package com.atombits.pocopaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class VisionAutomationAgentClientTest {

        @Test
        fun resolveAutomationEndpoint_usesGeminiGenerateContentRoute() {
                val client = VisionAutomationAgentClient()

                val endpoint = client.resolveAutomationEndpoint(
                        runtimeConfig = ProviderRuntimeConfig(
                                apiKey = "gemini-key",
                        model = "gemini-2.5-pro",
                                endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
                                apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
                        ),
                    selectedModel = "gemini-2.5-pro"
                )

                assertEquals(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent?key=gemini-key",
                        endpoint
                )
        }

        @Test
        fun extractAutomationResponseContent_readsGeminiCandidateText() {
                val client = VisionAutomationAgentClient()

                val content = client.extractAutomationResponseContent(
                        rawBody = """
                                {
                                    "candidates": [
                                        {
                                            "content": {
                                                "parts": [
                                                    {"thought": true, "text": "internal reasoning"},
                                                    {"text": "{\"message\":\"tap now\"}"}
                                                ]
                                            }
                                        }
                                    ]
                                }
                        """.trimIndent(),
                        apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
                )

                assertEquals("{\"message\":\"tap now\"}", content)
        }

        @Test
        fun parseAutomationAgentResponse_coercesTerminalFlowStateWhenExecutableActionPresent() {
                val response = parseAutomationAgentResponse(
                        """
                        {
                            "thought": "The confirm-payment button is visible.",
                            "action": {
                                "type": "tap",
                                "x": 0.5,
                                "y": 0.92
                            },
                            "message": "Tap the confirm payment button to complete payment.",
                            "flow_state": "completed",
                            "business_state": "success",
                            "execution_status": "ok",
                            "final_user_summary": "Payment completed.",
                            "semantic_context": {
                                "goal": "Complete JD battery purchase",
                                "expected_outcome": "Payment success page appears",
                                "verification_signals": ["Payment success page appears"],
                                "fallback_policy": {
                                    "max_attempts": 2,
                                    "on_failure": "request_human_guidance"
                                },
                                "approval": {
                                    "required": false,
                                    "approval_scope": "payment",
                                    "context_fingerprint": "jd_payment_confirm"
                                }
                            }
                        }
                        """.trimIndent()
                )

                assertEquals(AutomationFlowState.IN_PROGRESS, response.flowState)
                assertEquals("tap", response.action?.type)
                assertEquals("", response.finalUserSummary)
        }

    @Test
    fun createAutomationHttpClient_usesLamdaAiAlignedTimeouts() {
        val client = VisionAutomationAgentClient.createAutomationHttpClient()

        assertEquals(TimeUnit.SECONDS.toMillis(90).toInt(), client.callTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(15).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(60).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(60).toInt(), client.writeTimeoutMillis)
    }

    @Test
    fun buildAutomationFailureMessage_includesSanitizedDetailAndPreview() {
        val message = buildAutomationFailureMessage(
            prefix = "Automation response parse failed",
            detail = "JSONException:   Unexpected   token",
            preview = "{\n  \"message\": \"bad\"\n}"
        )

        assertTrue(message.contains("Automation response parse failed: JSONException: Unexpected token"))
        assertTrue(message.contains("preview={ \"message\": \"bad\" }"))
    }

    @Test
    fun throwableToAutomationDebugDetail_fallsBackToExceptionNameWhenMessageBlank() {
        assertEquals("IllegalStateException", IllegalStateException().toAutomationDebugDetail())
    }

    @Test
    fun buildAutomationDebugPreview_truncatesLongBodies() {
        val preview = buildAutomationDebugPreview("x".repeat(200), maxChars = 16)

        assertEquals("xxxxxxxxxxxxx...", preview)
    }
}