package com.atombits.pocopaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SemanticPrototypeClientTest {

		@Test
		fun parseSearchPlanResponse_extractsGoalProcessQueriesAndScope() {
				val client = SemanticPrototypeClient()
				val raw = """
						{
							"choices": [
								{
									"message": {
										"content": "{\"goal_summary\":\"比较两款手机\",\"process_summary\":\"先搜索，再汇总结果，再推理并回答\",\"search_queries\":[\"手机A 评测\",\"手机B 评测\"],\"search_scope\":[\"价格\",\"续航\"]}"
									}
								}
							]
						}
				""".trimIndent()

				val response = client.parseSearchPlanResponse(raw)

				assertEquals("比较两款手机", response.goalSummary)
				assertEquals("先搜索，再汇总结果，再推理并回答", response.processSummary)
				assertTrue(response.shouldSearch)
				assertEquals(listOf("手机A 评测", "手机B 评测"), response.searchQueries)
				assertEquals(listOf("价格", "续航"), response.searchScope)
				assertTrue(response.goalAndPlanContent.contains("比较两款手机"))
		}

		@Test
		fun normalizeGeminiResponseBody_mapsGenerateContentResponseToNormalizedShape() {
				val client = SemanticPrototypeClient()
				val normalized = client.normalizeGeminiResponseBody(
						"""
						{
						  "candidates": [
						    {
						      "content": {
						        "parts": [
						          {
						            "text": "{\"assistant_reply\":\"Gemini replied\"}"
						          }
						        ]
						      }
						    }
						  ],
						  "usageMetadata": {
						    "promptTokenCount": 12,
						    "candidatesTokenCount": 5,
						    "totalTokenCount": 17
						  }
						}
						""".trimIndent()
				)

				val normalizedJson = JSONObject(normalized)
				assertEquals(
						"{\"assistant_reply\":\"Gemini replied\"}",
						normalizedJson.getJSONArray("choices")
								.getJSONObject(0)
								.getJSONObject("message")
								.getString("content")
				)
				assertEquals(12, client.parseTokenUsage(normalized)?.promptTokens)
				assertEquals(5, client.parseTokenUsage(normalized)?.completionTokens)
				assertEquals(17, client.parseTokenUsage(normalized)?.totalTokens)
		}

		@Test
		fun parseSearchPlanResponse_allowsPlannerToDeclineSearch() {
				val client = SemanticPrototypeClient()
				val raw = """
						{
							"choices": [
								{
									"message": {
										"content": "{\"should_search\":false,\"goal_summary\":\"解释 TCP 和 UDP 的区别\",\"process_summary\":\"直接基于已有知识解释，不需要外部搜索\",\"search_queries\":[],\"search_scope\":[]}"
									}
								}
							]
						}
				""".trimIndent()

				val response = client.parseSearchPlanResponse(raw)

				assertFalse(response.shouldSearch)
				assertEquals(emptyList<String>(), response.searchQueries)
				assertEquals(emptyList<String>(), response.searchScope)
		}

		@Test
		fun buildRequestBodyJson_qwenModelUsesExplicitThinkingFlag() {
				val client = SemanticPrototypeClient()
				val requestBody = client.buildRequestBodyJson(
						promptMessages = listOf(PromptMessage(role = "user", content = "hi")),
						requestConfig = SemanticPrototypeClient.PromptRequestConfig(
								temperature = 0.2,
								topP = 0.85,
								maxTokens = 256,
								requestTag = "test"
						),
						runtimeConfig = ProviderRuntimeConfig(
								apiKey = "key",
								model = "qwen3.5-plus",
								endpoint = "https://semantic.test/v1/chat/completions",
								apiStyle = ProviderApiStyle.OPENAI_CHAT
						),
						turnOptions = ChatTurnOptions(thinkingEnabled = true, searchEnabled = true)
				)

				assertTrue(requestBody.getBoolean("enable_thinking"))
				assertFalse(requestBody.has("thinking"))
				assertFalse(requestBody.has("reasoning_effort"))
		}

		@Test
		fun buildRequestBodyJson_geminiModelUsesThinkingBudget() {
				val client = SemanticPrototypeClient()
				val requestBody = client.buildRequestBodyJson(
						promptMessages = listOf(PromptMessage(role = "user", content = "hi")),
						requestConfig = SemanticPrototypeClient.PromptRequestConfig(
								temperature = 0.2,
								topP = 0.85,
								maxTokens = 256,
								requestTag = "test"
						),
						runtimeConfig = ProviderRuntimeConfig(
								apiKey = "key",
								model = "gemini-3.5-flash",
								endpoint = "https://semantic.test/v1",
								apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
						),
						turnOptions = ChatTurnOptions(thinkingEnabled = false, searchEnabled = true)
				)

				assertEquals(
						0,
						requestBody
								.getJSONObject("generationConfig")
								.getJSONObject("thinkingConfig")
								.getInt("thinkingBudget")
				)
		}

		@Test
		fun buildRequestBodyJson_gptModelUsesReasoningEffort() {
				val client = SemanticPrototypeClient()
				val requestBody = client.buildRequestBodyJson(
						promptMessages = listOf(PromptMessage(role = "user", content = "hi")),
						requestConfig = SemanticPrototypeClient.PromptRequestConfig(
								temperature = 0.2,
								topP = 0.85,
								maxTokens = 256,
								requestTag = "test"
						),
						runtimeConfig = ProviderRuntimeConfig(
								apiKey = "key",
								model = "gpt-5.4",
								endpoint = "https://semantic.test/v1/chat/completions",
								apiStyle = ProviderApiStyle.OPENAI_CHAT
						),
						turnOptions = ChatTurnOptions(thinkingEnabled = true, searchEnabled = true)
				)

				assertEquals("high", requestBody.getString("reasoning_effort"))
				assertFalse(requestBody.has("enable_thinking"))
		}

		@Test
		fun buildRequestBodyJson_gptModelDisablesReasoningWithNone() {
				val client = SemanticPrototypeClient()
				val requestBody = client.buildRequestBodyJson(
						promptMessages = listOf(PromptMessage(role = "user", content = "hi")),
						requestConfig = SemanticPrototypeClient.PromptRequestConfig(
								temperature = 0.2,
								topP = 0.85,
								maxTokens = 256,
								requestTag = "test"
						),
						runtimeConfig = ProviderRuntimeConfig(
								apiKey = "key",
								model = "gpt-5.4-mini",
								endpoint = "https://semantic.test/v1/chat/completions",
								apiStyle = ProviderApiStyle.OPENAI_CHAT
						),
						turnOptions = ChatTurnOptions(thinkingEnabled = false, searchEnabled = true)
				)

				assertEquals("none", requestBody.getString("reasoning_effort"))
		}

		@Test
		fun consumeStreamingDataLine_stagesSearchSummaryBeforeReasoningAndAnswer() {
				val client = SemanticPrototypeClient()
				val accumulator = SemanticPrototypeClient.StreamingResponseAccumulator()
				val firstDelta = client.consumeStreamingDataLine(
						rawLine = """
								data: {"choices":[{"delta":{"reasoning_content":"第一段真实推理","content":"{\"search_summary\":\"搜索结果显示两款手机都支持快充\",\"assistant_reply\":\"如果你更重视续航，选A；如果更重视拍照，选B。\"}"}}]}
						""".trimIndent(),
						accumulator = accumulator
				)
				val secondDelta = client.consumeStreamingDataLine(
						rawLine = """
								data: {"choices":[{"delta":{"reasoning_content":"，第二段真实推理"}}]}
					""".trimIndent(),
						accumulator = accumulator
				)
				val thirdDelta = client.consumeStreamingDataLine(
						rawLine = """
								data: {"choices":[{"delta":{"reasoning_content":"，第三段真实推理"}}]}
					""".trimIndent(),
						accumulator = accumulator
				)

				assertEquals("搜索结果显示两款手机都支持快充", firstDelta?.searchSummaryContent)
				assertEquals(null, firstDelta?.reasoningContent)
				assertEquals("", firstDelta?.assistantReply)

				assertEquals("第一段真实推理，第二段真实推理", secondDelta?.reasoningContent)
				assertEquals("", secondDelta?.assistantReply)

				assertEquals("第一段真实推理，第二段真实推理，第三段真实推理", thirdDelta?.reasoningContent)
				assertEquals("如果你更重视续航，选A；如果更重视拍照，选B。", thirdDelta?.assistantReply)
		}

		@Test
		fun consumeStreamingDataLine_geminiStreamingAccumulatesStructuredJsonAndUsage() {
				val client = SemanticPrototypeClient()
				val accumulator = SemanticPrototypeClient.StreamingResponseAccumulator()
				val firstDelta = client.consumeStreamingDataLine(
						rawLine = """
							data: {"candidates":[{"content":{"parts":[{"text":"{\"search_summary\":\"Gemini已完成搜索\",\"assistant_reply\":\""}]}}]}
						""".trimIndent(),
						accumulator = accumulator,
						apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
				)
				client.consumeStreamingDataLine(
						rawLine = """
							data: {"candidates":[{"content":{"parts":[{"text":"Gemini给出结论\"}"}]}}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":4,"totalTokenCount":11}}
						""".trimIndent(),
						accumulator = accumulator,
						apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
				)
				val finalDelta = client.consumeStreamingDataLine(
						rawLine = "data: [DONE]",
						accumulator = accumulator,
						apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
				)

				assertEquals("Gemini已完成搜索", firstDelta?.searchSummaryContent)
				assertEquals("Gemini给出结论", finalDelta?.assistantReply)
				assertTrue(finalDelta?.completed == true)
				assertEquals(11, accumulator.tokenUsage?.totalTokens)
		}

			@Test
			fun consumeStreamingDataLine_waitsUntilSearchSummaryFieldCompletes() {
				val client = SemanticPrototypeClient()
				val accumulator = SemanticPrototypeClient.StreamingResponseAccumulator()
				val delta = client.consumeStreamingDataLine(
					rawLine = """
						data: {"choices":[{"delta":{"content":"{\"search_summary\":\"搜索结果显示A续航更强"}}]}
					""".trimIndent(),
					accumulator = accumulator
				)

				assertEquals(null, delta)
			}

		@Test
		fun parseResponse_searchEnhancedSemanticPayloadKeepsRawReasoningChannel() {
				val client = SemanticPrototypeClient()
				val raw = """
						{
							"choices": [
								{
									"message": {
										"reasoning_content": "这是 provider 输出的详细推理过程。",
										"content": "{\"assistant_reply\":\"最终建议选择A。\",\"semantic_summary\":\"比较两款手机并给出建议\",\"workflow_lane\":\"PASSIVE\",\"stage_owner\":\"USER\",\"passive_user_progress_signal\":\"CONTINUE_ACCUMULATING\",\"search_summary\":\"外部结果显示A续航更稳，B拍照更强\",\"visible_reasoning\":\"因为用户更关注续航，所以优先推荐A。\"}"
									}
								}
							]
						}
				""".trimIndent()

				val response = client.parseResponse(raw, PrototypeStoreData())

				assertEquals("外部结果显示A续航更稳，B拍照更强", response.searchSummaryContent)
				assertEquals("这是 provider 输出的详细推理过程。", response.reasoningContent)
				assertEquals("最终建议选择A。", response.assistantReply)
		}

		@Test
		fun parseResponse_threeWaySemanticEnvelopePreservesFields() {
				val client = SemanticPrototypeClient()
				val raw = """
						{
							"choices": [
								{
									"message": {
										"content": "{\"assistant_reply\":\"好的，马上执行。\",\"semantic_summary\":\"把 Wilson Clash 100 加入京东购物车\",\"workflow_lane\":\"PASSIVE\",\"stage_owner\":\"USER\",\"current_phase\":\"EXECUTION\",\"user_request_semantic\":\"START_EXECUTING\",\"stage_transition_recommendation\":\"SHOULD_ENTER_EXECUTING\",\"next_move\":\"start_execution\",\"phase_type\":\"execution\",\"phase_status\":\"start\",\"task_draft\":{\"action_code\":\"add_to_cart\",\"target_type\":\"product\",\"target_key\":\"Wilson Clash 100\",\"target_label\":\"Wilson Clash 100 网球拍\",\"capability_domain\":\"shopping\",\"reason_summary\":\"用户要求加入购物车但不结算\",\"detail_slots\":{\"common\":{\"platform\":\"京东\"},\"domain\":{\"product_type\":\"网球拍\"}}}}"
									}
								}
							]
						}
				""".trimIndent()

				val response = client.parseResponse(raw, PrototypeStoreData())

				assertEquals(CurrentPhase.EXECUTION, response.currentPhase)
				assertEquals(UserRequestSemantic.START_EXECUTING, response.userRequestSemantic)
				assertEquals(StageTransitionRecommendation.SHOULD_ENTER_EXECUTING, response.stageTransitionRecommendation)
				assertEquals(ConversationStage.EXECUTING, response.stage)
				assertEquals(UserProgressSignal.ENTER_EXECUTING, response.userProgressSignal)
				assertEquals(CurrentPhase.EXECUTION, response.semanticIntentState?.currentPhase)
				assertEquals(UserRequestSemantic.START_EXECUTING, response.semanticIntentState?.userRequestSemantic)
		}

		@Test
		fun parseResponse_structuredTaskDraftSlots_preservesStructuredShapeAndLegacySubset() {
				val client = SemanticPrototypeClient()
				val raw = """
						{
							"choices": [
								{
									"message": {
										"content": "{\"assistant_reply\":\"好的，我来准备短信。\",\"semantic_summary\":\"给张三发短信\",\"workflow_lane\":\"PASSIVE\",\"stage_owner\":\"USER\",\"passive_user_progress_signal\":\"ENTER_PREPARING\",\"task_draft\":{\"action_code\":\"send_message\",\"target_type\":\"contact\",\"target_key\":\"张三\",\"target_label\":\"张三\",\"capability_stack\":\"SYSTEM\",\"capability_domain\":\"communication\",\"capability_id\":\"system.sms\",\"process_id\":\"send_sms\",\"reason_summary\":\"已具备发送短信的必要信息\",\"detail_slots\":{\"common\":{\"platform\":\"sms\"},\"domain\":{\"recipient\":\"张三\",\"message_body\":\"今晚七点开会\",\"channel\":\"sms\"}}}}"
									}
								}
							]
						}
				""".trimIndent()

				val response = client.parseResponse(raw, PrototypeStoreData())
				val taskDraft = response.taskDraft

				assertEquals(ActionCode.SEND_MESSAGE, taskDraft?.actionCode)
				assertEquals("sms", taskDraft?.structuredDetailSlots?.common?.get(CommonDetailSlotKey.PLATFORM))
				assertEquals("张三", taskDraft?.structuredDetailSlots?.domain?.get("recipient"))
				assertEquals("今晚七点开会", taskDraft?.structuredDetailSlots?.domain?.get("message_body"))
				assertEquals("sms", taskDraft?.detailSlots?.get(DetailSlotKey.PLATFORM))
				assertFalse(taskDraft?.detailSlots?.values?.contains("今晚七点开会") == true)
		}
}
