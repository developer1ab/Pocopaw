package com.atombits.pocopaw.process.curation

import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.ProcessExemplarActionSummary
import com.atombits.pocopaw.ProcessLearningMaterial
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.PromptPacketType
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.VisionActionType
import com.atombits.pocopaw.buildReadyProcessAssetDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCurationRuntimeTest {

    @Test
    fun applyProcessExtractionCuration_queuesLearningMaterialWhenRawMaterialIsAbsent() {
        val learningMaterial = ProcessLearningMaterial(
            traceId = "trace-learning-1",
            processId = "jd_addtocart_process",
            appScope = "jd",
            domain = "shopping",
            objective = "在京东搜索5元白蜡烛并加入购物车",
            stageTransitions = listOf(
                "START | PROCESS_REFERENCE | runtime_started | 直接执行",
                "CLICK | VISION | 搜索框被激活 | 点击搜索框",
                "CLICK | VISION | 商品成功加入购物车 | 点击加入购物车"
            ),
            verificationSignals = listOf("搜索框被激活", "商品成功加入购物车"),
            exemplarActionSummaries = listOf(
                ProcessExemplarActionSummary(
                    stepType = "CLICK",
                    outcomeSignal = "搜索框被激活",
                    note = "点击搜索框"
                )
            )
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processLearningMaterials = mutableListOf(learningMaterial)
            ),
            now = 1200L,
            resolver = LocalProcessCurationResolver
        )

        assertTrue(outcome.applied)
        assertEquals("jd_addtocart_process", outcome.updatedStore.readyProcessAssets.single().processId)
        assertEquals("jd_addtocart_process", outcome.updatedStore.processAssetEntries.single().processScope)
        assertTrue(outcome.updatedStore.processExtractionConsumedIds.any { id -> id.startsWith("learning_material:") })
    }

        @Test
        fun parseProcessCurationResult_extractsStructuredDraftWithoutLocalOverlay() {
                val pendingEntry = ProcessAssetEntry(
                        domain = "SHOPPING",
                        appScope = "jd",
                        processScope = "jd_addtocart_process",
                        sourceType = ProcessAssetSourceType.TMP,
                        assetName = "shopping-jd-addtocart-temp1",
                        assetState = ProcessAssetState.PENDING,
                        taskExample = "在京东搜索5元白蜡烛并加入购物车",
                        semanticDescription = "fallback semantic should not leak",
                        planningTrace = listOf(
                                "OPEN_APP | APP_LAUNCH | app_launch_started | com.jingdong.app.mall",
                                "CLICK | VISION | 搜索框被激活，键盘弹出，可以输入搜索词 | 点击搜索框以激活输入",
                                "CLICK | VISION | 商品成功加入购物车 | 点击白蜡烛商品的加入购物车按钮"
                        ).joinToString(separator = "\n"),
                        businessAcceptanceCriteria = listOf("fallback_acceptance")
                )
                val traceBundle = ProcessTracePreprocessor.preprocess(pendingEntry, now = 1200L)
                val raw = """
                        {
                            "process_enum": "addtocart",
                            "semantic_description": "在京东通过搜索结果把目标商品加入购物车",
                            "optimized_business_process": {
                                "process_name": "jd add to cart optimized process",
                                "acceptance_criteria": ["商品成功加入购物车", "购物车数量增加"],
                                "stages": [
                                    {
                                        "stage_id": "s1",
                                        "stage_name_nl": "激活搜索框",
                                        "stage_goal_nl": "进入搜索输入态",
                                        "entry_signals": ["京东首页已显示"],
                                        "exit_signals": ["搜索框被激活"],
                                        "transition_conditions": ["点击搜索框后进入输入态"]
                                    },
                                    {
                                        "stage_id": "s2",
                                        "stage_name_nl": "搜索目标商品",
                                        "stage_goal_nl": "定位白蜡烛结果",
                                        "entry_signals": ["搜索框被激活"],
                                        "exit_signals": ["白蜡烛搜索结果可见"],
                                        "transition_conditions": ["输入关键词并触发搜索"]
                                    },
                                    {
                                        "stage_id": "s3",
                                        "stage_name_nl": "加入购物车",
                                        "stage_goal_nl": "完成购物车写入",
                                        "entry_signals": ["白蜡烛搜索结果可见"],
                                        "exit_signals": ["商品成功加入购物车"],
                                        "transition_conditions": ["点击加入购物车按钮"]
                                    }
                                ]
                            },
                            "optimized_process_trace": "STEP 1 -> 激活搜索框\nSTEP 2 -> 搜索目标商品\nSTEP 3 -> 加入购物车",
                            "diff_summary": "移除了无效绕路步骤",
                            "reliability_analysis": "优先保留可观察验收信号",
                            "decision": "replace",
                            "confidence": 0.91
                        }
                """.trimIndent()

                val result = parseProcessCurationResult(raw, traceBundle)

                assertEquals("addtocart", result.processEnum)
                assertEquals("在京东通过搜索结果把目标商品加入购物车", result.semanticDescription)
                assertEquals("jd add to cart optimized process", result.processName)
                assertEquals(listOf("商品成功加入购物车", "购物车数量增加"), result.acceptanceCriteria)
                assertEquals(3, result.stages.size)
                assertEquals(
                        listOf(
                                "STEP 1 -> 激活搜索框",
                                "STEP 2 -> 搜索目标商品",
                                "STEP 3 -> 加入购物车"
                        ),
                        result.optimizedProcessTrace
                )
                assertEquals("移除了无效绕路步骤", result.diffSummary)
                assertEquals("优先保留可观察验收信号", result.reliabilityAnalysis)
                assertEquals("replace", result.decision)
                assertEquals(0.91, result.confidence, 0.0)
        }

    @Test
    fun applyProcessExtractionCuration_derivesCanonicalProcessScopeFromNullSentinelRawMaterial() {
        val dirtyMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-null-1",
            candidateId = "candidate-null-1",
            selectedToolId = "app.com.jingdong.app.mall.open",
            processId = "null",
            objective = "在京东搜索5元白蜡烛并加入购物车",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf(
                "OPEN_APP | APP_LAUNCH | app_launch_started | com.jingdong.app.mall",
                "CLICK | VISION | 搜索框被激活，键盘弹出，可以输入搜索词 | 点击搜索框以激活输入;locator=search_box",
                "CLICK | VISION | 商品成功加入购物车 | 点击白蜡烛商品的加入购物车按钮;locator=add_to_cart_button"
            ),
            createdAt = 1000L,
            processAction = "addtocart"
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processExtractionRawMaterials = mutableListOf(dirtyMaterial)
            ),
            now = 1200L,
            resolver = LocalProcessCurationResolver
        )

        assertTrue(outcome.applied)
        assertEquals("jd_addtocart_process", outcome.updatedStore.readyProcessAssets.single().processId)
        assertEquals("jd_addtocart_process", outcome.updatedStore.processAssetEntries.single().processScope)
    }

    @Test
    fun runProcessCurationOnce_derivesCanonicalProcessScopeFromNullSentinelPendingEntry() {
        val pendingEntry = ProcessAssetEntry(
            domain = "SHOPPING",
            appScope = "jd",
            processScope = "jd_null_process",
            sourceType = ProcessAssetSourceType.TMP,
            assetName = "shopping-jd-addtocart-temp1",
            assetState = ProcessAssetState.PENDING,
            taskExample = "在京东搜索5元白蜡烛并加入购物车",
            semanticDescription = "在京东搜索5元白蜡烛并加入购物车",
            planningTrace = listOf(
                "OPEN_APP | APP_LAUNCH | app_launch_started | com.jingdong.app.mall",
                "CLICK | VISION | 搜索框被激活，键盘弹出，可以输入搜索词 | 点击搜索框以激活输入",
                "CLICK | VISION | 商品成功加入购物车 | 点击白蜡烛商品的加入购物车按钮"
            ).joinToString(separator = "\n"),
            businessAcceptanceCriteria = listOf("搜索框被激活", "键盘弹出", "商品成功加入购物车")
        )

        val outcome = runProcessCurationOnce(
            store = PrototypeStoreData(
                processAssetEntries = mutableListOf(pendingEntry)
            ),
            now = 1200L,
            resolver = LocalProcessCurationResolver
        )

        assertTrue(outcome.applied)
        assertEquals("jd_addtocart_process", outcome.updatedStore.readyProcessAssets.single().processId)
        assertEquals("jd_addtocart_process", outcome.updatedStore.processAssetEntries.single().processScope)
    }

    @Test
    fun runProcessCurationOnce_promotesPendingEntryIntoReadyAsset() {
        var capturedPacketType: PromptPacketType? = null
        val pendingEntry = ProcessAssetEntry(
            domain = "SHOPPING",
            appScope = "jd",
            processScope = "jd_buy_process",
            sourceType = ProcessAssetSourceType.TMP,
            assetName = "shopping-jd-buy-temp1",
            assetState = ProcessAssetState.PENDING,
            taskExample = "buy shoes",
            semanticDescription = "buy shoes",
            planningTrace = listOf(
                "OPEN_PRODUCT | PROCESS_REFERENCE | product_page_visible | locator=product_card;page=product_detail_v3",
                "SELECT_SKU | PROCESS_REFERENCE | sku_selected | locator=sku_chip;page=product_detail_v3"
            ).joinToString(separator = "\n"),
            businessAcceptanceCriteria = listOf("product_page_visible", "sku_selected")
        )

        val outcome = runProcessCurationOnce(
            store = PrototypeStoreData(
                processAssetEntries = mutableListOf(pendingEntry)
            ),
            now = 1200L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    capturedPacketType = packet.packetType
                    return StructuredProcessDraftResult(
                        processEnum = "jd_buy_process",
                        semanticDescription = "buy shoes",
                        processName = "jd buy optimized process",
                        acceptanceCriteria = listOf("product_page_visible", "sku_selected"),
                        stages = listOf(
                            BusinessProcessStage(
                                stageNameNl = "OPEN PRODUCT",
                                stageGoalNl = "product page visible",
                                exitSignals = listOf("product_page_visible")
                            ),
                            BusinessProcessStage(
                                stageNameNl = "SELECT SKU",
                                stageGoalNl = "sku selected",
                                exitSignals = listOf("sku_selected")
                            )
                        ),
                        optimizedProcessTrace = traceBundle.canonicalTrace,
                        diffSummary = "Promoted pending trace.",
                        reliabilityAnalysis = "Model-ready draft generated.",
                        decision = "add_variant",
                        confidence = 0.82,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        assertTrue(outcome.applied)
        assertEquals(PromptPacketType.PROCESS_CURATION_QUERY, capturedPacketType)
        assertEquals(1, outcome.updatedStore.readyProcessAssets.size)
        val readyAsset = outcome.updatedStore.readyProcessAssets.single()
        val readyEntry = outcome.updatedStore.processAssetEntries.single()
        assertEquals(ProcessAssetState.READY, readyEntry.assetState)
        assertEquals(buildReadyProcessAssetDisplayName(readyAsset), readyEntry.assetName)
        assertEquals("jd_buy_process", readyAsset.processId)
        assertEquals(listOf("OPEN PRODUCT", "SELECT SKU"), readyAsset.stageReferences.map { it.stageName })
        assertEquals(listOf("product_page_visible", "sku_selected"), readyAsset.verificationSignals)
        assertEquals(2, readyAsset.exemplarActionSummaries.size)
        assertTrue(readyAsset.pageSemanticAnchors.any { anchor -> anchor.locatorHints.contains("product_card") })
        assertTrue(readyAsset.referenceWeight > 0.0)
        assertTrue(
            outcome.updatedStore.processAssetEvents.any { event ->
                event.eventType == ProcessAssetEventType.PROMOTED_READY
            }
        )
        assertTrue(outcome.updatedStore.lastProcessCurationSummary?.summary?.contains("stage=done") == true)
        assertFalse(outcome.updatedStore.lastProcessCurationSummary?.summary?.contains("stage=done:base_only") == true)
    }

    @Test
    fun runProcessCurationOnce_namesDeleteCartReadyAssetAsClearcart() {
        val pendingEntry = ProcessAssetEntry(
            domain = "SHOPPING",
            appScope = "jd",
            processScope = "shopping-jd-delete-cart",
            sourceType = ProcessAssetSourceType.TMP,
            assetName = "shopping-jd-clearcart-temp1",
            assetState = ProcessAssetState.PENDING,
            taskExample = "购物车",
            semanticDescription = "用户打开京东App，进入购物车页面，进入管理模式，选择商品并删除，购物车更新显示剩余商品或空状态。",
            planningTrace = listOf(
                "CLICK | VISION | 进入购物车页面 | 点击购物车入口",
                "CLICK | VISION | 删除按钮可见 | 点击管理按钮",
                "VERIFY | VISION | 购物车已清空 | 删除成功"
            ).joinToString(separator = "\n"),
            businessAcceptanceCriteria = listOf("进入购物车页面", "删除按钮可见", "购物车已清空")
        )

        val outcome = runProcessCurationOnce(
            store = PrototypeStoreData(
                processAssetEntries = mutableListOf(pendingEntry)
            ),
            now = 1200L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    return StructuredProcessDraftResult(
                        processEnum = "shopping-jd-delete-cart",
                        semanticDescription = pendingEntry.semanticDescription,
                        processName = "jd delete optimized process",
                        acceptanceCriteria = listOf("进入购物车页面", "删除按钮可见", "购物车已清空"),
                        stages = listOf(
                            BusinessProcessStage(stageNameNl = "进入购物车"),
                            BusinessProcessStage(stageNameNl = "删除商品")
                        ),
                        optimizedProcessTrace = traceBundle.canonicalTrace,
                        diffSummary = "Promoted delete-cart trace.",
                        reliabilityAnalysis = "Delete-cart should canonicalize to clearcart.",
                        decision = "add_variant",
                        confidence = 0.87,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        assertTrue(outcome.applied)
        val readyEntry = outcome.updatedStore.processAssetEntries.single()
        val readyAsset = outcome.updatedStore.readyProcessAssets.single()
        assertEquals("clearcart", readyAsset.processAction)
        assertEquals(buildReadyProcessAssetDisplayName(readyAsset), readyEntry.assetName)
        assertEquals("shopping-jd-clearcart-path1-v1", readyEntry.assetName)
        assertFalse(readyEntry.assetName.contains("addtocart"))
    }

    @Test
    fun runProcessCurationOnce_supersedesLegacyReadyRevisionWhenConcreteReadyProcessIdIsPromoted() {
        val legacyReadyEntry = ProcessAssetEntry(
            id = "legacy-ready-1",
            domain = "SHOPPING",
            appScope = "jd",
            processScope = "jd_addtocart_process",
            sourceType = ProcessAssetSourceType.TMP,
            assetName = "shopping-jd-addtocart-path2-v1",
            semanticDescription = "在京东搜索卷纸并加入购物车",
            assetState = ProcessAssetState.READY,
            businessProcessName = "addtocart",
            businessAcceptanceCriteria = listOf("旧版已完成加入购物车"),
            businessStagesJson = "[{\"stage_name_nl\":\"LEGACY SEARCH\"},{\"stage_name_nl\":\"LEGACY ADD\"}]",
            revision = 1,
            updatedAt = 1000L,
            assetUpdatedAt = 1000L,
            readyWeight = 0.6,
            successCount = 1
        )
        val pendingEntry = ProcessAssetEntry(
            id = "pending-addtocart-1",
            domain = "SHOPPING",
            appScope = "jd",
            processScope = "jd_addtocart_process",
            sourceType = ProcessAssetSourceType.TMP,
            assetName = "shopping-jd-addtocart-temp9",
            assetState = ProcessAssetState.PENDING,
            taskExample = "在京东搜索卷纸并加入购物车",
            semanticDescription = "在京东搜索卷纸并加入购物车",
            planningTrace = listOf(
                "CLICK | VISION | 搜索框激活 | 点击搜索框准备输入",
                "INPUT | VISION | 已输入卷纸关键词 | 输入搜索词卷纸",
                "CLICK | VISION | 商品加入购物车成功 | 点击第一个商品的加入购物车按钮"
            ).joinToString(separator = "\n"),
            businessAcceptanceCriteria = listOf("搜索框激活", "商品加入购物车成功")
        )

        val outcome = runProcessCurationOnce(
            store = PrototypeStoreData(
                processAssetEntries = mutableListOf(legacyReadyEntry, pendingEntry),
                readyProcessAssets = mutableListOf(
                    com.atombits.pocopaw.ReadyProcessAsset(
                        processId = "jd_addtocart_process",
                        domain = "SHOPPING",
                        appScope = "jd",
                        semanticDescription = "在京东搜索卷纸并加入购物车",
                        stages = listOf("SEARCH", "ADD"),
                        acceptanceCriteria = listOf("旧版已完成加入购物车"),
                        version = 1,
                        lineageSourceTraceId = "legacy-ready-1",
                        processAction = "addtocart",
                        pathIndex = 2
                    )
                )
            ),
            now = 1200L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    return StructuredProcessDraftResult(
                        processEnum = "jd_addtocart_process",
                        semanticDescription = pendingEntry.semanticDescription,
                        processName = "jd add to cart optimized process",
                        acceptanceCriteria = listOf("搜索框激活", "商品加入购物车成功", "购物车数量增加"),
                        stages = listOf(
                            BusinessProcessStage(
                                stageNameNl = "SEARCH",
                                stageGoalNl = "搜索卷纸"
                            ),
                            BusinessProcessStage(
                                stageNameNl = "ADD",
                                stageGoalNl = "加入购物车"
                            )
                        ),
                        optimizedProcessTrace = traceBundle.canonicalTrace,
                        diffSummary = "Promoted improved add-to-cart variant.",
                        reliabilityAnalysis = "Concrete ready process id preserved for local execution clone.",
                        decision = "add_variant",
                        confidence = 0.9,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        val legacyEntry = outcome.updatedStore.processAssetEntries.first { entry -> entry.id == "legacy-ready-1" }
        val promotedEntry = outcome.updatedStore.processAssetEntries.first { entry -> entry.id == "pending-addtocart-1" }

        assertTrue(outcome.applied)
        assertEquals(ProcessAssetState.SUPERSEDED, legacyEntry.assetState)
        assertEquals(ProcessAssetState.READY, promotedEntry.assetState)
        assertEquals("shopping-jd-addtocart-path2-v2", promotedEntry.assetName)
        assertEquals("jd_addtocart_process", promotedEntry.processScope)
        val promotedReadyAsset = outcome.updatedStore.readyProcessAssets.first { asset ->
            buildReadyProcessAssetDisplayName(asset) == promotedEntry.assetName
        }
        assertEquals(2, promotedReadyAsset.version)
        assertEquals(2, promotedReadyAsset.pathIndex)
        assertTrue(
            outcome.updatedStore.processAssetEvents.any { event ->
                event.eventType == ProcessAssetEventType.SUPERSEDED &&
                    event.assetEntryId == "legacy-ready-1"
            }
        )
    }

    @Test
    fun applyProcessExtractionCuration_queuesTraceRichRawMaterialWithoutLegacyBindings() {
        val matureMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-queued-trace-rich-1",
            candidateId = "candidate-queued-trace-rich-1",
            selectedToolId = "app.jd.search",
            processId = "jd_buy_process",
            objective = "buy shoes",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf(
                "OPEN_PRODUCT | PROCESS_REFERENCE | product_page_visible | locator=product_card;page=product_detail_v3;target=0.41,0.11",
                "SELECT_SKU | PROCESS_REFERENCE | sku_selected | locator=sku_chip;page=product_detail_v3;target=0.58,0.74"
            ),
            createdAt = 1000L
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processExtractionRawMaterials = mutableListOf(matureMaterial)
            ),
            now = 1300L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    return StructuredProcessDraftResult(
                        processEnum = pendingEntry.processScope,
                        semanticDescription = pendingEntry.semanticDescription,
                        processName = "jd buy optimized process",
                        acceptanceCriteria = listOf("product_page_visible", "sku_selected"),
                        stages = listOf(
                            BusinessProcessStage(stageNameNl = "OPEN PRODUCT"),
                            BusinessProcessStage(stageNameNl = "SELECT SKU")
                        ),
                        optimizedProcessTrace = traceBundle.canonicalTrace,
                        diffSummary = "Curated from trace-rich raw material.",
                        reliabilityAnalysis = "Trace evidence is sufficient without legacy bindings.",
                        decision = "add_variant",
                        confidence = 0.82,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        assertTrue(outcome.applied)
        assertEquals(1, outcome.updatedStore.readyProcessAssets.size)
        assertEquals(1, outcome.updatedStore.processExtractionConsumedIds.size)
        val readyAsset = outcome.updatedStore.readyProcessAssets.single()
        assertTrue(readyAsset.pageSemanticAnchors.any { anchor -> anchor.locatorHints.contains("product_card") })
        assertTrue(readyAsset.pageSemanticAnchors.any { anchor -> anchor.locatorHints.contains("sku_chip") })
        assertEquals(2, readyAsset.exemplarActionSummaries.size)
    }

        @Test
        fun runProcessCurationOnce_recordsSemanticPathInExecutionEvents() {
                val pendingEntry = ProcessAssetEntry(
                        domain = "SHOPPING",
                        appScope = "jd",
                        processScope = "jd_addtocart_process",
                        sourceType = ProcessAssetSourceType.TMP,
                        assetName = "shopping-jd-addtocart-temp1",
                        assetState = ProcessAssetState.PENDING,
                        taskExample = "在京东搜索酱油并加入购物车",
                        semanticDescription = "在京东搜索酱油并加入购物车",
                        planningTrace = listOf(
                                "CLICK | VISION | 搜索框激活，键盘弹出 | 点击搜索框准备输入",
                                "INPUT | VISION | 搜索框内显示“酱油” | 输入搜索关键词“酱油”"
                        ).joinToString(separator = "\n"),
                        businessAcceptanceCriteria = listOf("搜索框激活，键盘弹出", "搜索框内显示“酱油”")
                )

                val resolver = SemanticProcessCurationResolver(
                        isConfiguredOverride = { true },
                        requestPromptPacketOverride = {
                                """
                                {
                                    "process_enum": "jd_addtocart_process",
                                    "semantic_description": "在京东APP中搜索酱油，并将商品加入购物车",
                                    "optimized_business_process": {
                                        "process_name": "jd add to cart optimized process",
                                        "acceptance_criteria": ["搜索框激活，键盘弹出", "搜索框内显示“酱油”"],
                                        "stages": [
                                            {
                                                "stage_id": "s1",
                                                "stage_name_nl": "激活搜索框",
                                                "stage_goal_nl": "进入输入态",
                                                "entry_signals": [],
                                                "exit_signals": ["搜索框激活，键盘弹出"],
                                                "transition_conditions": ["点击搜索框准备输入"]
                                            },
                                            {
                                                "stage_id": "s2",
                                                "stage_name_nl": "输入关键词",
                                                "stage_goal_nl": "完成搜索词输入",
                                                "entry_signals": ["搜索框激活，键盘弹出"],
                                                "exit_signals": ["搜索框内显示“酱油”"],
                                                "transition_conditions": ["输入搜索关键词“酱油”"]
                                            }
                                        ]
                                    },
                                    "optimized_process_trace": [
                                        "CLICK | VISION | 搜索框激活，键盘弹出 | 点击搜索框准备输入",
                                        "INPUT | VISION | 搜索框内显示“酱油” | 输入搜索关键词“酱油”"
                                    ],
                                    "diff_summary": "Preserved executable payload.",
                                    "reliability_analysis": "Semantic model returned a valid structured process draft.",
                                    "decision": "add_variant",
                                    "confidence": 0.88
                                }
                                """.trimIndent()
                        }
                )

                val outcome = runProcessCurationOnce(
                        store = PrototypeStoreData(
                                processAssetEntries = mutableListOf(pendingEntry)
                        ),
                        now = 1200L,
                        resolver = resolver
                )

                assertTrue(outcome.applied)
                assertTrue(
                        outcome.updatedStore.executionEvents.any { event ->
                    event.summary.startsWith("Process curation path: semantic")
                        }
                )
                assertTrue(outcome.message.contains("Process curation path: semantic"))
        }

    @Test
    fun applyProcessExtractionCuration_queuesMatureRawMaterialIntoPendingAndCuratesIt() {
        var capturedPacketType: PromptPacketType? = null
        val matureMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-queued-1",
            candidateId = "candidate-queued-1",
            selectedToolId = "app.jd.search",
            processId = "jd_buy_process",
            objective = "buy shoes",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf(
                "OPEN_PRODUCT | PROCESS_REFERENCE | product_page_visible | locator=product_card;page=product_detail_v3",
                "SELECT_SKU | PROCESS_REFERENCE | sku_selected | locator=sku_chip;page=product_detail_v3"
            ),
            createdAt = 1000L
        )
        val immatureMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-queued-2",
            candidateId = "candidate-queued-2",
            selectedToolId = "app.jd.search",
            processId = "jd_buy_process",
            objective = "buy shoes later",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf("OPEN_PRODUCT | PROCESS_REFERENCE | product_page_visible"),
            createdAt = 1100L
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processExtractionRawMaterials = mutableListOf(matureMaterial, immatureMaterial)
            ),
            now = 1300L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    capturedPacketType = packet.packetType
                    return StructuredProcessDraftResult(
                        processEnum = pendingEntry.processScope,
                        semanticDescription = pendingEntry.semanticDescription,
                        processName = "jd buy optimized process",
                        acceptanceCriteria = listOf("product_page_visible", "sku_selected"),
                        stages = listOf(
                            BusinessProcessStage(
                                stageNameNl = "OPEN PRODUCT",
                                stageGoalNl = "product page visible",
                                exitSignals = listOf("product_page_visible")
                            ),
                            BusinessProcessStage(
                                stageNameNl = "SELECT SKU",
                                stageGoalNl = "sku selected",
                                exitSignals = listOf("sku_selected")
                            )
                        ),
                        optimizedProcessTrace = traceBundle.canonicalTrace,
                        diffSummary = "Curated from mature raw material.",
                        reliabilityAnalysis = "Stable enough for ready reuse.",
                        decision = "add_variant",
                        confidence = 0.8,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        assertTrue(outcome.applied)
        assertEquals(PromptPacketType.PROCESS_CURATION_QUERY, capturedPacketType)
        assertEquals(1, outcome.updatedStore.readyProcessAssets.size)
        assertEquals(1, outcome.updatedStore.processExtractionConsumedIds.size)
        assertEquals(matureMaterial.id, outcome.updatedStore.processExtractionConsumedIds.single())
        assertEquals(1, outcome.updatedStore.processAssetEntries.size)
        assertEquals(ProcessAssetState.READY, outcome.updatedStore.processAssetEntries.single().assetState)
        assertTrue(outcome.message.contains("Curated 1 ready process asset"))
        assertTrue(outcome.message.contains("Deferred 1 immature group"))
    }

    @Test
    fun applyProcessExtractionCuration_preservesExecutableBindingsWhenDraftTraceIsAbstracted() {
        val matureMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-payload-1",
            candidateId = "candidate-payload-1",
            selectedToolId = "app.com.jingdong.app.mall.open",
            processId = "jd_cart_process",
            objective = "在京东搜索酱油并加入购物车",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf(
                "CLICK | VISION | 搜索框激活，键盘弹出 | 点击搜索框准备输入;locator=search_box;target=0.41,0.11",
                "INPUT | VISION | 搜索框内显示“酱油” | 输入搜索关键词“酱油”;text=酱油;target=0.5,0.08"
            ),
            createdAt = 1000L,
            processAction = "addtocart"
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processExtractionRawMaterials = mutableListOf(matureMaterial)
            ),
            now = 1300L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    return StructuredProcessDraftResult(
                        processEnum = "jd_cart_process",
                        semanticDescription = pendingEntry.semanticDescription,
                        processName = "jd add to cart optimized process",
                        acceptanceCriteria = listOf("搜索框激活", "键盘弹出", "搜索框文本变为“酱油”"),
                        stages = listOf(
                            BusinessProcessStage(stageNameNl = "激活搜索框"),
                            BusinessProcessStage(stageNameNl = "输入关键词")
                        ),
                        optimizedProcessTrace = listOf(
                            "STEP 1 -> 激活搜索框",
                            "STEP 2 -> 输入关键词"
                        ),
                        diffSummary = "Abstracted trace for optimization.",
                        reliabilityAnalysis = "Should still preserve executable payload from raw material.",
                        decision = "add_variant",
                        confidence = 0.84,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        assertTrue(outcome.applied)
        val readyAsset = outcome.updatedStore.readyProcessAssets.single()
        assertTrue(readyAsset.pageSemanticAnchors.isNotEmpty() || readyAsset.exemplarActionSummaries.isNotEmpty())
        assertTrue(readyAsset.pageSemanticAnchors.any { anchor -> anchor.semanticRole.contains("search", ignoreCase = true) || anchor.notes.any { note -> note.contains("搜索") } })
        assertTrue(readyAsset.exemplarActionSummaries.any { exemplar -> exemplar.stepType == "INPUT" })
    }

    @Test
    fun applyProcessExtractionCuration_preservesExecutableBindingsAcrossStructuralSteps() {
        val matureMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-structural-payload-1",
            candidateId = "candidate-structural-payload-1",
            selectedToolId = "app.com.jingdong.app.mall.open",
            processId = "jd_cart_process",
            objective = "在京东搜索酱油并加入购物车",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf(
                "START | SHORTCUT | runtime_started | 直接执行",
                "OPEN_APP | APP_LAUNCH | app_launch_started | com.jingdong.app.mall",
                "CLICK | VISION | Search bar becomes active, keyboard appears | Tap on the search bar to begin entering the search term.;locator=search_box;target=0.45,0.115",
                "INPUT | VISION | Text '酱油' appears in search field | Input '酱油' into the search field;locator=search_box;text=酱油;target=0.5,0.12",
                "CLICK | VISION | Search results page displays soy sauce products | Tap the search button to execute the search for soy sauce;locator=search_button;target=0.875,0.117",
                "CLICK | VISION | Product added to cart, cart icon shows increment | Tap the add-to-cart button for the first soy sauce product;locator=first_product_add_to_cart;target=0.944,274.0",
                "VERIFY | VISION | Product added to cart successfully | 商品已加入购物车"
            ),
            createdAt = 1000L,
            processAction = "addtocart"
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processExtractionRawMaterials = mutableListOf(matureMaterial)
            ),
            now = 1300L,
            resolver = object : ProcessCurationResolver {
                override fun resolve(
                    packet: PromptPacket,
                    pendingEntry: ProcessAssetEntry,
                    traceBundle: CanonicalProcessTraceBundle,
                    now: Long
                ): StructuredProcessDraftResult {
                    return StructuredProcessDraftResult(
                        processEnum = "jd_cart_process",
                        semanticDescription = pendingEntry.semanticDescription,
                        processName = "jd add to cart optimized process",
                        acceptanceCriteria = listOf(
                            "Keyboard visibility",
                            "Cursor in search field",
                            "Search field contains '酱油'",
                            "Product list visible",
                            "Cart badge number increases"
                        ),
                        stages = listOf(
                            BusinessProcessStage(stageNameNl = "激活搜索框"),
                            BusinessProcessStage(stageNameNl = "输入关键词"),
                            BusinessProcessStage(stageNameNl = "执行搜索"),
                            BusinessProcessStage(stageNameNl = "加入购物车")
                        ),
                        optimizedProcessTrace = listOf(
                            "STEP 1 -> 激活搜索框",
                            "STEP 2 -> 输入关键词",
                            "STEP 3 -> 执行搜索",
                            "STEP 4 -> 加入购物车"
                        ),
                        diffSummary = "Abstracted trace for optimization.",
                        reliabilityAnalysis = "Should still preserve executable payload from raw material with structural steps.",
                        decision = "add_variant",
                        confidence = 0.84,
                        traceBundle = traceBundle
                    )
                }
            }
        )

        assertTrue(outcome.applied)
        val readyEntry = outcome.updatedStore.processAssetEntries.single()
        val traceLines = readyEntry.planningTrace.lines()
        assertTrue(traceLines.any { line -> line.startsWith("CLICK |") && line.contains("target=0.45,0.115") })
        assertTrue(traceLines.any { line -> line.startsWith("INPUT |") && line.contains("text=酱油") })
        assertFalse(traceLines.any { line -> line.startsWith("START |") && line.contains("target=") })
        assertFalse(traceLines.any { line -> line.startsWith("OPEN_APP |") && line.contains("target=") })

        val readyAsset = outcome.updatedStore.readyProcessAssets.single()
        assertTrue(readyAsset.pageSemanticAnchors.isNotEmpty() || readyAsset.exemplarActionSummaries.isNotEmpty())
        assertTrue(readyAsset.pageSemanticAnchors.any { anchor -> anchor.semanticRole == "search_box" } || readyAsset.exemplarActionSummaries.any { exemplar -> exemplar.locatorHint == "search_box" })
        assertTrue(readyAsset.pageSemanticAnchors.any { anchor -> anchor.semanticRole == "search_button" } || readyAsset.exemplarActionSummaries.any { exemplar -> exemplar.locatorHint == "search_button" })
        assertTrue(readyAsset.exemplarActionSummaries.any { exemplar -> exemplar.stepType == "INPUT" })
    }

    @Test
    fun applyProcessExtractionCuration_returnsNoMatureMessageWhenOnlyImmatureRawMaterialExists() {
        val immatureMaterial = com.atombits.pocopaw.CanonicalTraceRawMaterial(
            traceId = "trace-immature-1",
            candidateId = "candidate-immature-1",
            selectedToolId = "app.jd.search",
            processId = "jd_buy_process",
            objective = "buy shoes",
            lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
            steps = listOf("OPEN_PRODUCT | PROCESS_REFERENCE | product_page_visible"),
            createdAt = 1000L
        )

        val outcome = applyProcessExtractionCuration(
            store = PrototypeStoreData(
                processExtractionRawMaterials = mutableListOf(immatureMaterial)
            ),
            now = 1200L,
            resolver = LocalProcessCurationResolver
        )

        assertFalse(outcome.applied)
        assertTrue(outcome.updatedStore.readyProcessAssets.isEmpty())
        assertTrue(outcome.updatedStore.processExtractionConsumedIds.isEmpty())
        assertTrue(outcome.message.contains("No mature"))
    }
}
