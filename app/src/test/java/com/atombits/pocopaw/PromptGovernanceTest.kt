package com.atombits.pocopaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptGovernanceTest {

	@Test
	fun buildSearchPlanPacket_usesDedicatedPacketTypeAndContract() {
		val packet = PromptCenter.buildSearchPlanPacket(
			SearchPlanPromptSpec(
				currentState = LocalConversationState(),
				historyLines = "USER: 帮我查一下东京旅游攻略",
				semanticContextBundle = "current_task=travel_research",
				reactivationHintLines = "none",
				userMessage = "帮我查一下东京旅游攻略"
			)
		)

		assertEquals(PromptPacketType.SEARCH_PLAN_QUERY, packet.packetType)
		assertEquals(480, packet.tokenBudget.requestMaxTokens)
		assertTrue(packet.responseContract.contains("\"should_search\""))
		assertTrue(packet.responseContract.contains("search_queries"))
		assertTrue(packet.promptMessages.first().content.contains("Search decision contract"))
		assertTrue(packet.promptMessages.first().content.contains("Decide whether this turn actually needs external search"))
		assertTrue(packet.promptMessages[1].content.contains("Local time authority:"))
		assertTrue(packet.promptMessages[1].content.contains("current_local_date="))
		assertTrue(packet.promptMessages[1].content.contains("current_local_region_status="))
	}

	@Test
	fun buildSemanticTurnPacket_searchEnhancedExpandsContractAndBudget() {
		val packet = PromptCenter.buildSemanticTurnPacket(
			SemanticTurnPromptSpec(
				currentState = LocalConversationState(),
				historyLines = "USER: 帮我比较两款手机",
				semanticContextBundle = "current_task=compare_phones",
				reactivationHintLines = "none",
				userMessage = "结合搜索结果帮我比较两款手机",
				searchPlanBundle = "goal_summary=比较两款手机\nprocess_summary=先搜索再汇总推理",
				searchBundle = "provider=aliyun_opensearch_web_search\nquery=手机评测"
			)
		)

		assertEquals(PromptPacketType.SEMANTIC_TURN, packet.packetType)
		assertEquals(2400, packet.tokenBudget.requestMaxTokens)
		assertTrue(packet.responseContract.contains("\"search_summary\""))
		assertTrue(!packet.responseContract.contains("\"visible_reasoning\""))
		assertTrue(packet.activeSections.contains("search_plan_bundle"))
		assertEquals(
			"goal_summary=比较两款手机\nprocess_summary=先搜索再汇总推理",
			packet.searchPlanBundle
		)
	}

	@Test
	fun buildSemanticTurnPacket_standardTurnKeepsBaseContract() {
		val packet = PromptCenter.buildSemanticTurnPacket(
			SemanticTurnPromptSpec(
				currentState = LocalConversationState(),
				historyLines = "USER: 今天天气怎么样",
				semanticContextBundle = "current_task=weather_question",
				reactivationHintLines = "none",
				userMessage = "今天天气怎么样"
			)
		)

		assertEquals(2200, packet.tokenBudget.requestMaxTokens)
		assertTrue(!packet.responseContract.contains("\"search_summary\""))
		assertTrue(!packet.activeSections.contains("search_plan_bundle"))
	}

	@Test
	fun buildSemanticTurnPacket_usesResolvedLocalRegionAuthorityWhenProvided() {
		val packet = withLocalRegionAuthoritySectionForTest(
			"""
			current_local_region_status=resolved
			current_local_region_source=test_override
			current_local_country=China
			current_local_admin_region=Shanghai
			current_local_city=Shanghai
			current_local_district=Huangpu District
			""".trimIndent()
		) {
			PromptCenter.buildSemanticTurnPacket(
				SemanticTurnPromptSpec(
					currentState = LocalConversationState(),
					historyLines = "USER: 今天天气怎么样",
					semanticContextBundle = "current_task=weather_question",
					reactivationHintLines = "none",
					userMessage = "今天天气怎么样"
				)
			)
		}

		assertTrue(packet.promptMessages[1].content.contains("current_local_city=Shanghai"))
		assertTrue(packet.promptMessages[1].content.contains("current_local_district=Huangpu District"))
		assertTrue(packet.systemContract.contains("use the provided local city or district as the default anchor"))
	}

	@Test
	fun buildSemanticTurnPacket_contractUsesCurrentRuntimeEnums() {
		val packet = PromptCenter.buildSemanticTurnPacket(
			SemanticTurnPromptSpec(
				currentState = LocalConversationState(),
				historyLines = "USER: 帮我查一下机票",
				semanticContextBundle = "current_task=flight_search",
				reactivationHintLines = "none",
				userMessage = "帮我查一下机票"
			)
		)

		val expectedActionValues = ActionCode.values().joinToString("|") { value -> value.wireName }
		val expectedTargetTypeValues = TargetType.values().joinToString("|") { value -> value.wireName }
		val expectedCapabilityDomainValues = CapabilityDomain.values().joinToString("|") { value -> value.wireName }

		assertTrue(packet.responseContract.contains("\"action_code\":\"${expectedActionValues}|null\""))
		assertTrue(packet.responseContract.contains("\"target_type\":\"${expectedTargetTypeValues}|null\""))
		assertTrue(packet.responseContract.contains("\"capability_domain\":\"${expectedCapabilityDomainValues}|null\""))
		assertTrue(packet.responseContract.contains("\"current_phase\":\"ACCUMULATION|PREPARATION|EXECUTION\""))
		assertTrue(packet.responseContract.contains("\"user_request_semantic\":\"START_ACCUMULATING|START_PREPARING|START_EXECUTING\""))
		assertTrue(packet.responseContract.contains("\"stage_transition_recommendation\":\"SHOULD_ENTER_ACCUMULATING|SHOULD_ENTER_PREPARING|SHOULD_ENTER_EXECUTING|null\""))
		assertTrue(!packet.responseContract.contains("commerce_service"))
		assertTrue(!packet.responseContract.contains("map_navigation"))
	}

	@Test
	fun buildSemanticIntentSystemContract_keepsGroundedCommunicationGuardrails() {
		val contract = PromptCenter.buildSemanticIntentSystemContract()

		assertTrue(contract.contains(buildAssistantIdentityInstruction()))
		assertTrue(contract.contains("must come from provided local/system authority or search evidence"))
		assertTrue(contract.contains("do not invent, backfill, or restate stale pretraining facts as current truth"))
		assertTrue(contract.contains("do not treat adjacent same-domain capabilities from the capability prior bundle as blocking alternatives"))
		assertTrue(contract.contains("preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots"))
		assertTrue(contract.contains("present confirmation-needed defaults as assumptions or revision hooks"))
		assertTrue(contract.contains("For communication send_message tasks, a named recipient plus message_body and channel is sufficient grounding for execution"))
		assertTrue(contract.contains("current_phase values: ACCUMULATION, PREPARATION, EXECUTION"))
		assertTrue(contract.contains("user_request_semantic values: START_ACCUMULATING, START_PREPARING, START_EXECUTING"))
		assertTrue(contract.contains("assistant_reply is the complete user-visible content for this turn"))
		assertTrue(contract.contains("START_ACCUMULATING: answer, compare, explain, or explore with concrete content"))
		assertTrue(contract.contains("START_PREPARING: assistant_reply must contain the actual first proposal/plan in the same turn"))
		assertTrue(contract.contains("Requests to create a shortlist, shopping list, recommendation list, itinerary, checklist, or step plan are START_PREPARING"))
		assertTrue(contract.contains("Requests for a simple plan to open, check, inspect, or view device settings/status without executing now are START_PREPARING"))
		assertTrue(contract.contains("Pure compare/explore/go broader/no plan turns stay START_ACCUMULATING"))
		assertTrue(contract.contains("missing preferences are non-blocking by default"))
		assertTrue(contract.contains("START_EXECUTING requires the latest user message to semantically ask"))
		assertTrue(contract.contains("Read-only device operations such as opening settings, viewing a screen, checking status, or inspecting a setting are executable"))
		assertTrue(contract.contains("START_EXECUTING: if the task is grounded, task_draft must be non-null and concrete"))
		assertTrue(contract.contains("For read-only settings/status inspection execution, ground task_draft as action_code=open"))
		assertTrue(contract.contains("SHOULD_ENTER_EXECUTING only means readiness"))
		assertTrue(contract.contains("Do not output placeholder replies such as 请稍等"))
		assertTrue(contract.contains("Passive turns prioritize dialogue context, then topic slots, then local preferences, then search"))
		assertTrue(contract.contains("Runtime packets never receive new search evidence"))
	}

	@Test
	fun buildSemanticTurnPacket_userPromptCarriesThreeStageOutputObligations() {
		val packet = PromptCenter.buildSemanticTurnPacket(
			SemanticTurnPromptSpec(
				currentState = LocalConversationState(
					currentPhase = CurrentPhase.PREPARATION,
					userRequestSemantic = UserRequestSemantic.START_PREPARING,
					stageTransitionRecommendation = StageTransitionRecommendation.SHOULD_ENTER_PREPARING
				),
				historyLines = "USER: 给我出个欧洲十日游的详细方案吧",
				semanticContextBundle = "task=欧洲十日游方案; phase=preparing",
				reactivationHintLines = "none",
				userMessage = "好的"
			)
		)

		val userPrompt = packet.promptMessages[1].content
		assertTrue(userPrompt.contains("preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots"))
		assertTrue(userPrompt.contains("frame confirmation-needed defaults as assumptions or revision invites"))
		assertTrue(userPrompt.contains("assistant_reply is the complete user-visible content for this turn"))
		assertTrue(userPrompt.contains("Do not output placeholder replies such as 请稍等"))
		assertTrue(userPrompt.contains("For START_PREPARING, assistant_reply must contain the actual first proposal/plan in the same turn"))
		assertTrue(userPrompt.contains("Requests to create a shortlist, shopping list, recommendation list, itinerary, checklist, or step plan are START_PREPARING"))
		assertTrue(userPrompt.contains("Requests for a simple plan to open, check, inspect, or view device settings/status without executing now are START_PREPARING"))
		assertTrue(userPrompt.contains("Pure compare/explore/go broader/no plan turns stay START_ACCUMULATING"))
		assertTrue(userPrompt.contains("For travel, shopping, recommendations, schedules, and similar planning tasks, missing preferences are non-blocking by default"))
		assertTrue(userPrompt.contains("For START_EXECUTING with a grounded task, task_draft must be non-null and concrete"))
		assertTrue(userPrompt.contains("Read-only device operations such as opening settings, viewing a screen, checking status, or inspecting a setting are executable"))
		assertTrue(userPrompt.contains("For read-only settings/status inspection execution, ground task_draft as action_code=open"))
		assertTrue(userPrompt.contains("use START_ACCUMULATING and answer, compare, explain, or explore with concrete content"))
		assertTrue(userPrompt.contains("no shorter than the user's requested deliverable requires"))
	}

	@Test
	fun buildSemanticTurnPacket_suppressesStaleExecutionContextForExplicitNewTask() {
		val packet = PromptCenter.buildSemanticTurnPacket(
			SemanticTurnPromptSpec(
				currentState = LocalConversationState(
					currentTaskRecord = TaskRecord(
						taskId = "task-1",
						sourceTurnId = "turn-1",
						phase = TaskPhase.EXECUTING,
						actionCode = ActionCode.OPEN,
						targetType = TargetType.APP,
						targetKey = "com.taobao.taobao",
						targetLabel = "淘宝",
						detailSlots = mapOf(DetailSlotKey.PLATFORM to "淘宝"),
						capabilityStack = CapabilityStack.APP,
						capabilityDomain = CapabilityDomain.SHOPPING,
						capabilityId = "app.com.taobao.taobao.open"
					),
					pendingExecutionRecovery = com.atombits.pocopaw.process.runtime.ProcessRecoveryContext(
						objective = "淘宝",
						blockedContext = "screen capture failed",
						recoveryAction = "request_human_guidance",
						selectedToolId = "app.com.taobao.taobao.open"
					)
				),
				historyLines = "USER: 去淘宝清空一下购物车",
				semanticContextBundle = "current_task=clear_taobao_cart",
				reactivationHintLines = "none",
				executionBrief = "objective=淘宝; action=open; plan=open 淘宝",
				userMessage = "去软件商店装个猫眼app"
			)
		)

		val userPrompt = packet.promptMessages[1].content
		assertFalse(userPrompt.contains("Current execution brief:"))
		assertFalse(userPrompt.contains("Pending execution recovery:"))
		assertFalse(userPrompt.contains("objective=淘宝"))
	}

	@Test
	fun buildSemanticTurnPacket_keepsExecutionRecoveryForEllipticalFollowUp() {
		val packet = PromptCenter.buildSemanticTurnPacket(
			SemanticTurnPromptSpec(
				currentState = LocalConversationState(
					currentTaskRecord = TaskRecord(
						taskId = "task-1",
						sourceTurnId = "turn-1",
						phase = TaskPhase.EXECUTING,
						actionCode = ActionCode.OPEN,
						targetType = TargetType.APP,
						targetKey = "猫眼",
						targetLabel = "猫眼",
						detailSlots = mapOf(DetailSlotKey.PLATFORM to "应用商店"),
						capabilityDomain = CapabilityDomain.SYSTEM_CONTROL
					),
					pendingExecutionRecovery = com.atombits.pocopaw.process.runtime.ProcessRecoveryContext(
						objective = "猫眼",
						blockedContext = "screen capture failed",
						recoveryAction = "request_human_guidance"
					)
				),
				historyLines = "USER: 去应用商店给我安装一下猫眼",
				semanticContextBundle = "current_task=install_maoyan",
				reactivationHintLines = "none",
				executionBrief = "objective=猫眼; action=open; plan=open 猫眼",
				userMessage = "去给我安装一下"
			)
		)

		val userPrompt = packet.promptMessages[1].content
		assertTrue(userPrompt.contains("Current execution brief:"))
		assertTrue(userPrompt.contains("Pending execution recovery:"))
		assertTrue(userPrompt.contains("objective=猫眼"))
	}

	@Test
	fun buildSearchPlanPacket_contractLimitsSearchToPreRuntimePlanning() {
		val packet = PromptCenter.buildSearchPlanPacket(
			SearchPlanPromptSpec(
				currentState = LocalConversationState(),
				historyLines = "USER: 帮我订今晚附近的川菜馆",
				semanticContextBundle = "current_task=restaurant_booking",
				reactivationHintLines = "none",
				userMessage = "帮我订今晚附近的川菜馆"
			)
		)

		val systemPrompt = packet.promptMessages.first().content
		assertTrue(systemPrompt.contains("allowed only before runtime starts"))
		assertTrue(systemPrompt.contains("cold-start explicit execution requests"))
		assertTrue(systemPrompt.contains("must not be used after PreparedExecutionStart/runtime has begun"))
		assertTrue(systemPrompt.contains("preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots"))
		assertTrue(systemPrompt.contains("do not search for blocked options"))
	}

	@Test
	fun offlineDialoguePreferenceExtractionContract_usesStructuredPreferenceAndBiasSignals() {
		val contract = ResponseContractRegistry.offlineDialoguePreferenceExtractionContract()

		assertTrue(contract.contains("preference_facts"))
		assertTrue(contract.contains("interaction_bias_signals"))
		assertTrue(contract.contains("facet_key"))
		assertTrue(contract.contains("signal_key"))
		assertTrue(!contract.contains("preference_evidence"))
	}

	@Test
	fun buildProcessReferenceSelectionPacket_carriesRecommendedSlotValuesAsWeakHints() {
		val packet = PromptCenter.buildProcessReferenceSelectionPacket(
			ProcessReferenceSelectionPromptSpec(
				taskIntentBundle = "task_slot_keys=shopping.product_type\npreserved_slot_values=shopping.product_type=battery\nrecommended_slot_values=common.price=10-20,shopping.brand=nanfu",
				processCatalogBundle = "asset_id=1 | asset_name=buy_battery_flow | score=12.0",
				processGuidanceBundle = "domain_scope=shopping",
				maxSelectionCount = 3
			)
		)

		assertTrue(packet.promptMessages.first().content.contains("recommended_slot_values are weak hints only"))
		assertTrue(packet.promptMessages.first().content.contains("must never override preserved_slot_values"))
		assertTrue(packet.promptMessages[1].content.contains("recommended_slot_values=common.price=10-20,shopping.brand=nanfu"))
		assertTrue(packet.promptMessages[1].content.contains("Use recommended_slot_values only as weak hints for missing primary filters"))
	}

	@Test
	fun buildSemanticIntentSystemContract_namesAssistantAsXiaoZhuaZhua() {
		val contract = PromptCenter.buildSemanticIntentSystemContract()

		assertTrue(contract.contains(ASSISTANT_NAME_ZH))
		assertTrue(contract.contains(ASSISTANT_NAME_EN))
		assertTrue(contract.contains("never as popopaw"))
	}

	@Test
	fun buildAutomationAgentPacket_keepsSystemIntentLaunchAuthorityLocal() {
		val packet = PromptCenter.buildAutomationAgentPacket(
			AutomationQueryPromptSpec(
				objective = "给爸爸发短信",
				plan = "打开短信并发送消息",
				step = 1,
				selectedToolId = SYSTEM_INTENT_SENDTO_SMS,
				captureWidth = 720,
				captureHeight = 1612
			)
		)

		val systemPrompt = packet.promptMessages.first().content
		assertTrue(systemPrompt.contains("System intents are launched locally before visual automation starts"))
		assertTrue(!systemPrompt.contains("system intents must be bare sys://system.intent.xxx"))
		assertTrue(systemPrompt.contains("Execution search-freeze rule"))
		assertTrue(systemPrompt.contains("do not request, rely on, or invent live web/search evidence"))
	}

	@Test
	fun buildVisionGroundingPacket_keepsSearchEvidenceOutOfRuntimePrompt() {
		val packet = PromptCenter.buildVisionGroundingPacket(
			VisionGroundingRequest(
				objective = "搜索老陈醋并加入购物车",
				expectedOutcome = "商品加入购物车",
				imageDataUrl = "data:image/png;base64,AA==",
				captureWidth = 720,
				captureHeight = 1612
			)
		)

		val systemPrompt = packet.promptMessages.first().content
		assertTrue(systemPrompt.contains("Do not request, rely on, or inject live web/search evidence"))
		assertTrue(systemPrompt.contains("execution facts must already be frozen before runtime"))
	}

	@Test
	fun buildAutomationAgentPacket_includesAuthoritativeResolvedSlotsInBoundaryPrompt() {
		val packet = PromptCenter.buildAutomationAgentPacket(
			AutomationQueryPromptSpec(
				objective = "给Melody发微信",
				plan = "发送 hi 给 Melody",
				step = 1,
				executionBoundaryPacket = TaskExecutionBoundaryPacket(
					taskId = "test-task",
					taskUpdatedAt = 1L,
					phase = TaskPhase.EXECUTING,
					actionCode = ActionCode.SEND_MESSAGE,
					targetType = TargetType.GENERIC,
					targetKey = "Melody",
					targetLabel = "Melody",
					structuredDetailSlots = TaskDetailSlots(
						domain = linkedMapOf(
							"recipient" to "Melody",
							"message_body" to "hi",
							"channel" to "wechat"
						)
					),
					capabilityDomain = CapabilityDomain.COMMUNICATION,
					executionGateFlag = ExecutionGateFlag.READY_TO_START
				),
				captureWidth = 720,
				captureHeight = 1612
			)
		)

		val systemPrompt = packet.promptMessages.first().content
		val userPrompt = packet.promptMessages[1].content

		assertTrue(systemPrompt.contains("authoritative resolved slots"))
		assertTrue(systemPrompt.contains("Do not paraphrase, embellish, translate, or silently substitute them"))
		assertTrue(systemPrompt.contains("Constraint monotonicity principle"))
		assertTrue(systemPrompt.contains("never silently replace a more specific constraint set with a broader projection"))
		assertTrue(userPrompt.contains("Authoritative resolved slots:"))
		assertTrue(userPrompt.contains("communication.recipient=Melody"))
		assertTrue(userPrompt.contains("communication.message_body=hi"))
		assertTrue(userPrompt.contains("communication.channel=wechat"))
	}

	@Test
	fun buildAutomationAgentPacket_usesTargetKeyAsExecutionTargetWhenDisplayLabelIsBroader() {
		val packet = PromptCenter.buildAutomationAgentPacket(
			AutomationQueryPromptSpec(
				objective = "Wilson Hyper Hammer 5.3",
				plan = "add_to_cart Wilson Hyper Hammer 5.3",
				step = 1,
				executionBoundaryPacket = TaskExecutionBoundaryPacket(
					taskId = "jd-wilson-task",
					taskUpdatedAt = 1L,
					phase = TaskPhase.EXECUTING,
					actionCode = ActionCode.ADD_TO_CART,
					targetType = TargetType.GENERIC,
					targetKey = "Wilson Hyper Hammer 5.3",
					targetLabel = "Wilson网球拍",
					structuredDetailSlots = TaskDetailSlots(
						domain = linkedMapOf(
							"product_type" to "网球拍",
							"brand" to "Wilson",
							"spec" to "Hyper Hammer 5.3"
						)
					),
					capabilityDomain = CapabilityDomain.SHOPPING,
					executionGateFlag = ExecutionGateFlag.READY_TO_START
				),
				captureWidth = 1080,
				captureHeight = 2400
			)
		)

		val userPrompt = packet.promptMessages[1].content
		assertTrue(userPrompt.contains("target=Wilson Hyper Hammer 5.3"))
		assertTrue(userPrompt.contains("display_label=Wilson网球拍"))
		assertTrue(userPrompt.contains("Objective summary:\nWilson Hyper Hammer 5.3"))
		assertTrue(userPrompt.contains("Plan summary:\nadd_to_cart Wilson Hyper Hammer 5.3"))
		assertTrue(userPrompt.contains("TARGET_OBJECT=Wilson Hyper Hammer 5.3"))
	}

	@Test
	fun buildAutomationAgentPacket_requiresExecutableInputForAuthoritativeTextEntry() {
		val packet = PromptCenter.buildAutomationAgentPacket(
			AutomationQueryPromptSpec(
				objective = "去亚马逊购物搜索手表",
				plan = "打开亚马逊并搜索手表",
				step = 2,
				executionBoundaryPacket = TaskExecutionBoundaryPacket(
					taskId = "amazon-task",
					taskUpdatedAt = 1L,
					phase = TaskPhase.EXECUTING,
					actionCode = ActionCode.ADD_TO_CART,
					targetType = TargetType.GENERIC,
					targetKey = "手表",
					targetLabel = "手表",
					structuredDetailSlots = TaskDetailSlots(
						domain = linkedMapOf(
							"product_type" to "手表"
						)
					),
					capabilityDomain = CapabilityDomain.SHOPPING,
					executionGateFlag = ExecutionGateFlag.READY_TO_START
				),
				captureWidth = 1080,
				captureHeight = 2400
			)
		)

		val systemPrompt = packet.promptMessages.first().content
		assertTrue(systemPrompt.contains("must make a new authoritative value appear in an editable field"))
		assertTrue(systemPrompt.contains("return action.type=inputText"))
		assertTrue(systemPrompt.contains("visible clickable suggestion, chip, or key"))
		assertTrue(systemPrompt.contains("IME recovery exception"))
		assertTrue(systemPrompt.contains("visible keyboard clipboard icon"))
	}
}
