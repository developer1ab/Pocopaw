package com.atombits.pocopaw

import com.atombits.pocopaw.intent.IntentGateway
import com.atombits.pocopaw.reply.ChatReplyGateway
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRoutingRuntimeTest {
    @Test
    fun semanticGateways_routeUsingActiveProfileAfterProfileSwitch() = runBlocking {
        val originalConfig = ProviderProfileRuntime.current()
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(openAiSemanticPayloadResponse()))
        }
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(geminiSemanticPayloadResponse()))
        }
        server.start()

        try {
            val profileA = testProfileConfig(
                semanticProvider = SemanticProviderKind.OPENAI,
                semanticApiKey = "semantic-key-a",
                semanticEndpoint = server.url("/profile-a").toString(),
                semanticFastModel = "openai-fast-a",
                semanticExpertModel = "openai-expert-a",
                visionProvider = VisionProviderKind.GEMINI_VISION,
                visionApiKey = "vision-key-a",
                visionEndpoint = "https://vision-a.test/v1",
                visionFastModel = "gemini-vision-fast-a",
                visionExpertModel = "gemini-vision-expert-a"
            )
            val profileB = testProfileConfig(
                semanticProvider = SemanticProviderKind.GEMINI,
                semanticApiKey = "semantic-key-b",
                semanticEndpoint = server.url("/profile-b").toString(),
                semanticFastModel = "gemini-fast-b",
                semanticExpertModel = "gemini-expert-b",
                visionProvider = VisionProviderKind.OPENAI_VISION,
                visionApiKey = "vision-key-b",
                visionEndpoint = "https://vision-b.test/v1",
                visionFastModel = "openai-vision-fast-b",
                visionExpertModel = "openai-vision-expert-b"
            )

            val intentGateway = IntentGateway(SemanticPrototypeClient())
            val chatReplyGateway = ChatReplyGateway(SemanticPrototypeClient())

            ProviderProfileRuntime.update(profileA)
            val storeA = PrototypeStoreData(
                semanticRuntimePreferences = SemanticRuntimePreferences(modelTier = SemanticModelTier.EXPERT)
            )
            intentGateway.planTurn(userMessage = "first", store = storeA)
            chatReplyGateway.buildExecutionAssistantReply(factsBundle = "facts-a", store = storeA)

            ProviderProfileRuntime.update(profileB)
            val storeB = PrototypeStoreData(
                semanticRuntimePreferences = SemanticRuntimePreferences(modelTier = SemanticModelTier.EXPERT)
            )
            intentGateway.planTurn(userMessage = "second", store = storeB)
            chatReplyGateway.buildExecutionAssistantReply(factsBundle = "facts-b", store = storeB)

            val request1 = server.takeRequest()
            val request2 = server.takeRequest()
            val request3 = server.takeRequest()
            val request4 = server.takeRequest()

            assertOpenAiCapturedRequest(request1.path.orEmpty(), request1.getHeader("Authorization").orEmpty(), request1.body.readUtf8(), "/profile-a", "semantic-key-a", "openai-expert-a")
            assertOpenAiCapturedRequest(request2.path.orEmpty(), request2.getHeader("Authorization").orEmpty(), request2.body.readUtf8(), "/profile-a", "semantic-key-a", "openai-expert-a")
            assertGeminiCapturedRequest(request3.path.orEmpty(), request3.getHeader("Authorization").orEmpty(), request3.body.readUtf8(), "/profile-b/gemini-expert-b:generateContent?key=semantic-key-b")
            assertGeminiCapturedRequest(request4.path.orEmpty(), request4.getHeader("Authorization").orEmpty(), request4.body.readUtf8(), "/profile-b/gemini-expert-b:generateContent?key=semantic-key-b")
        } finally {
            server.shutdown()
            ProviderProfileRuntime.update(originalConfig)
        }
    }

    @Test
    fun visionRuntimeConfig_resolvesApiKeyEndpointAndModelFromCurrentProfile() {
        val originalConfig = ProviderProfileRuntime.current()
        try {
            val profileA = testProfileConfig(
                semanticProvider = SemanticProviderKind.OPENAI,
                semanticApiKey = "semantic-key-a",
                semanticEndpoint = "https://semantic-a.test/v1/chat/completions",
                semanticFastModel = "openai-fast-a",
                semanticExpertModel = "openai-expert-a",
                visionProvider = VisionProviderKind.GEMINI_VISION,
                visionApiKey = "vision-key-a",
                visionEndpoint = "https://vision-a.test/v1",
                visionFastModel = "gemini-vision-fast-a",
                visionExpertModel = "gemini-vision-expert-a",
                visionTier = SemanticModelTier.EXPERT
            )
            val profileB = testProfileConfig(
                semanticProvider = SemanticProviderKind.GEMINI,
                semanticApiKey = "semantic-key-b",
                semanticEndpoint = "https://semantic-b.test/v1/chat/completions",
                semanticFastModel = "gemini-fast-b",
                semanticExpertModel = "gemini-expert-b",
                visionProvider = VisionProviderKind.OPENAI_VISION,
                visionApiKey = "vision-key-b",
                visionEndpoint = "https://vision-b.test/v1",
                visionFastModel = "openai-vision-fast-b",
                visionExpertModel = "openai-vision-expert-b"
            )

            ProviderProfileRuntime.update(profileA)
            val visionA = ProviderRuntimeConfigs.vision
            assertEquals(VisionProviderKind.GEMINI_VISION, ProviderRuntimeConfigs.visionProviderKind())
            assertEquals("vision-key-a", visionA.apiKey)
            assertEquals("https://vision-a.test/v1", visionA.endpoint)
            assertEquals("gemini-vision-expert-a", visionA.model)

            ProviderProfileRuntime.update(profileB)
            val visionB = ProviderRuntimeConfigs.vision
            assertEquals(VisionProviderKind.OPENAI_VISION, ProviderRuntimeConfigs.visionProviderKind())
            assertEquals("vision-key-b", visionB.apiKey)
            assertEquals("https://vision-b.test/v1", visionB.endpoint)
            assertEquals("openai-vision-fast-b", visionB.model)
        } finally {
            ProviderProfileRuntime.update(originalConfig)
        }
    }

    @Test
    fun buildCustomProviderProfile_collapsesSemanticSelectionToCurrentTierModel() {
        val currentConfig = testProfileConfig(
            semanticProvider = SemanticProviderKind.OPENAI,
            semanticApiKey = "semantic-key-a",
            semanticEndpoint = "https://semantic-a.test/v1/chat/completions",
            semanticFastModel = "gpt-5.4-mini",
            semanticExpertModel = "gpt-5.4",
            visionProvider = VisionProviderKind.GEMINI_VISION,
            visionApiKey = "vision-key-a",
            visionEndpoint = "https://vision-a.test/v1",
            visionFastModel = "gemini-3.5-flash",
            visionExpertModel = "gemini-3.1-pro-preview",
            visionTier = SemanticModelTier.EXPERT
        )

        val customConfig = buildCustomProviderProfile(currentConfig, SemanticModelTier.EXPERT)

        assertEquals(ProviderProfileId.CUSTOM, customConfig.profileId)
        assertEquals(RegionMode.CUSTOM, customConfig.regionMode)
        assertEquals("gpt-5.4", customConfig.semantic.fastModel)
        assertEquals("gpt-5.4", customConfig.semantic.expertModel)
        assertEquals("gemini-3.1-pro-preview", customConfig.vision.fastModel)
        assertEquals("gemini-3.1-pro-preview", customConfig.vision.expertModel)
        assertEquals("gemini-3.1-pro-preview", customConfig.vision.model)
    }

    @Test
    fun customModelHelpers_updateProviderAndModelFromSelectedOption() {
        val currentConfig = testProfileConfig(
            semanticProvider = SemanticProviderKind.OPENAI,
            semanticApiKey = "semantic-key-a",
            semanticEndpoint = "https://semantic-a.test/v1/chat/completions",
            semanticFastModel = "gpt-5.4-mini",
            semanticExpertModel = "gpt-5.4",
            visionProvider = VisionProviderKind.GEMINI_VISION,
            visionApiKey = "vision-key-a",
            visionEndpoint = "https://vision-a.test/v1",
            visionFastModel = "gemini-3.5-flash",
            visionExpertModel = "gemini-3.1-pro-preview"
        )

        val semanticUpdated = withCustomSemanticModel(currentConfig, "qwen3.6-plus")
        val visionUpdated = withCustomVisionModel(currentConfig, "gpt-5.4")

        assertEquals(ProviderProfileId.CUSTOM, semanticUpdated.profileId)
        assertEquals(RegionMode.CUSTOM, semanticUpdated.regionMode)
        assertEquals(SemanticProviderKind.QWEN, semanticUpdated.semantic.provider)
        assertEquals("qwen3.6-plus", semanticUpdated.semantic.fastModel)
        assertEquals("qwen3.6-plus", semanticUpdated.semantic.expertModel)

        assertEquals(ProviderProfileId.CUSTOM, visionUpdated.profileId)
        assertEquals(RegionMode.CUSTOM, visionUpdated.regionMode)
        assertEquals(VisionProviderKind.OPENAI_VISION, visionUpdated.vision.provider)
        assertEquals("gpt-5.4", visionUpdated.vision.fastModel)
        assertEquals("gpt-5.4", visionUpdated.vision.expertModel)
        assertEquals("gpt-5.4", visionUpdated.vision.model)
    }

    @Test
    fun withVisionModelTier_switchesBetweenFastAndExpertModels() {
        val currentConfig = testProfileConfig(
            semanticProvider = SemanticProviderKind.OPENAI,
            semanticApiKey = "semantic-key-a",
            semanticEndpoint = "https://semantic-a.test/v1/chat/completions",
            semanticFastModel = "gpt-5.4-mini",
            semanticExpertModel = "gpt-5.4",
            visionProvider = VisionProviderKind.QWEN_VISION,
            visionApiKey = "vision-key-a",
            visionEndpoint = "https://vision-a.test/v1",
            visionFastModel = "qwen3.5-plus",
            visionExpertModel = "qwen3.6-plus"
        )

        val expertConfig = withVisionModelTier(currentConfig, SemanticModelTier.EXPERT)

        assertEquals(SemanticModelTier.EXPERT, expertConfig.vision.modelTier)
        assertEquals("qwen3.6-plus", expertConfig.vision.model)
    }

    @Test
    fun modelControlSupport_keepsSemanticAndVisionLanesSeparated() {
        val semanticControls = resolveSemanticModelControls("qwen3.5-plus")
        val visionControls = resolveVisionModelControls("qwen3.5-plus")

        assertTrue(semanticControls.thinkingSupported)
        assertTrue(semanticControls.searchSupported)
        assertFalse(visionControls.thinkingSupported)
        assertFalse(visionControls.searchSupported)
        assertFalse(CUSTOM_VISION_MODEL_OPTIONS.any { option -> option.startsWith("deepseek") })
    }

    private fun assertOpenAiCapturedRequest(
        actualPath: String,
        actualAuthorization: String,
        actualRequestBody: String,
        expectedPath: String,
        expectedApiKey: String,
        expectedModel: String
    ) {
        assertEquals(expectedPath, actualPath)
        assertEquals("Bearer $expectedApiKey", actualAuthorization)
        val model = JSONObject(actualRequestBody).optString("model")
        assertEquals(expectedModel, model)
    }

    private fun assertGeminiCapturedRequest(
        actualPath: String,
        actualAuthorization: String,
        actualRequestBody: String,
        expectedPath: String
    ) {
        assertEquals(expectedPath, actualPath)
        assertEquals("", actualAuthorization)

        val requestJson = JSONObject(actualRequestBody)
        assertEquals("application/json", requestJson.getJSONObject("generationConfig").getString("responseMimeType"))
        assertEquals("user", requestJson.getJSONArray("contents").getJSONObject(0).getString("role"))
    }

    private fun openAiSemanticPayloadResponse(): String {
        return """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"assistant_reply\":\"ok\",\"semantic_summary\":\"summary\",\"workflow_lane\":\"PASSIVE\",\"stage_owner\":\"USER\",\"passive_user_progress_signal\":\"CONTINUE_ACCUMULATING\"}"
                  }
                }
              ]
            }
        """.trimIndent()
    }

        private fun geminiSemanticPayloadResponse(): String {
                return """
                        {
                            "candidates": [
                                {
                                    "content": {
                                        "parts": [
                                            {
                                                "text": "{\"assistant_reply\":\"ok\",\"semantic_summary\":\"summary\",\"workflow_lane\":\"PASSIVE\",\"stage_owner\":\"USER\",\"passive_user_progress_signal\":\"CONTINUE_ACCUMULATING\"}"
                                            }
                                        ]
                                    }
                                }
                            ]
                        }
                """.trimIndent()
        }

    private fun testProfileConfig(
        semanticProvider: SemanticProviderKind,
        semanticApiKey: String,
        semanticEndpoint: String,
        semanticFastModel: String,
        semanticExpertModel: String,
        visionProvider: VisionProviderKind,
        visionApiKey: String,
        visionEndpoint: String,
        visionFastModel: String,
        visionExpertModel: String,
        visionTier: SemanticModelTier = SemanticModelTier.FAST
    ): ProviderProfileRuntimeConfig {
        return ProviderProfileRuntimeConfig(
            profileId = ProviderProfileId.CUSTOM,
            regionMode = RegionMode.CUSTOM,
            semantic = SemanticProviderRuntimeConfig(
                provider = semanticProvider,
                apiKey = semanticApiKey,
                endpoint = semanticEndpoint,
                fastModel = semanticFastModel,
                expertModel = semanticExpertModel
            ),
            vision = VisionProviderRuntimeConfig(
                provider = visionProvider,
                apiKey = visionApiKey,
                endpoint = visionEndpoint,
                fastModel = visionFastModel,
                expertModel = visionExpertModel,
                modelTier = visionTier
            ),
            search = SearchProviderRuntimeConfig(
                provider = SearchProviderKind.GOOGLE_CSE,
                apiKey = "search-key",
                endpoint = "https://search.test/v1",
                engineId = "cx",
                gl = "us",
                hl = "en",
                safe = "active"
            )
        )
    }

}
