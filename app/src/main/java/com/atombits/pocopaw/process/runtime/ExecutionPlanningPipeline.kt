package com.atombits.pocopaw.process.runtime

import com.atombits.pocopaw.ActionCode
import com.atombits.pocopaw.ExecutionCheck
import com.atombits.pocopaw.ExecutionLaunchDirective
import com.atombits.pocopaw.ExecutionRouteEntryType
import com.atombits.pocopaw.MessageRole
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.TaskPhase
import com.atombits.pocopaw.TaskRecord
import com.atombits.pocopaw.ToolCapability
import com.atombits.pocopaw.ToolspaceCatalogManager
import com.atombits.pocopaw.findReusableProcessAssetRecordName
import com.atombits.pocopaw.isSystemIntentCapabilityId
import com.atombits.pocopaw.normalizeExecutionBoundaryPacketForRuntime
import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.reuse.ProcessGuidanceLayer
import com.atombits.pocopaw.process.reuse.ProcessReuseRuntime
import com.atombits.pocopaw.requiresScreenCapturePermissionForExecutionStart
import com.atombits.pocopaw.resolveCurrentProcessReuseContext
import com.atombits.pocopaw.resolveCurrentState
import com.atombits.pocopaw.resolveExecutionBoundaryPacketWithReadyProcesses
import com.atombits.pocopaw.resolveExecutionLaunchDirective
import com.atombits.pocopaw.resolvePreparedProcessReuseContextForStart
import com.atombits.pocopaw.resolveProcessShortcutCandidate
import com.atombits.pocopaw.resolveReadyProcessAsset
import com.atombits.pocopaw.resolveTaskFirstCandidate
import com.atombits.pocopaw.resolvedProcessAction
import com.atombits.pocopaw.toTaskExecutionBoundaryPacket
import com.atombits.pocopaw.updateCurrentIntentSlice

data class PreparedExecutionStart(
    val taskId: String,
    val taskUpdatedAt: Long,
    @Transient
    val capabilityId: String? = null,
    @Transient
    val processId: String? = null,
    @Transient
    val routeDecisionSource: String? = null,
    @Transient
    val routeReasonSummary: String? = null,
    @Transient
    val verificationChecks: List<ExecutionCheck> = emptyList(),
    @Transient
    val processReuseContext: CandidateProcessReferenceContext? = null,
    @Transient
    val guidanceLayer: ProcessGuidanceLayer? = null,
    val launchDirective: ExecutionLaunchDirective,
    val startSummary: String,
    @Transient
    val routeInfo: String? = null,
    @Transient
    val routeEntryType: ExecutionRouteEntryType? = null
)

internal interface ExecutionCapabilityCatalog {
    fun listCapabilities(): List<ToolCapability>

    fun findCapabilityById(capabilityId: String?): ToolCapability?

    fun resolveDefaultCapabilityForTask(task: String): ToolCapability?

    suspend fun refreshFromDevice()
}

internal class ToolspaceExecutionCapabilityCatalog(
    private val toolspaceCatalogManager: ToolspaceCatalogManager
) : ExecutionCapabilityCatalog {
    override fun listCapabilities(): List<ToolCapability> {
        return toolspaceCatalogManager.listCapabilities()
    }

    override fun findCapabilityById(capabilityId: String?): ToolCapability? {
        return toolspaceCatalogManager.findCapabilityById(capabilityId)
    }

    override fun resolveDefaultCapabilityForTask(task: String): ToolCapability? {
        return toolspaceCatalogManager.resolveDefaultCapabilityForTask(task)
    }

    override suspend fun refreshFromDevice() {
        toolspaceCatalogManager.refreshFromDevice()
    }
}

private data class ProcessExplorationRouteEcho(
    val startSummary: String,
    val routeInfo: String,
    val routeEntryType: ExecutionRouteEntryType
)

internal class ExecutionPlanningPipeline(
    private val capabilityCatalog: ExecutionCapabilityCatalog,
    private val hasScreenCapturePermission: () -> Boolean
) {
    suspend fun prepare(store: PrototypeStoreData): PreparedExecutionStart? {
        val currentTaskRecord = store.resolveCurrentState().currentTaskRecord ?: return null
        if (currentTaskRecord.phase != TaskPhase.EXECUTING) {
            return null
        }
        val resolvedBoundaryPacket = currentTaskRecord.toTaskExecutionBoundaryPacket()
        val boundaryPacketWithCapability = resolveExecutionCapabilityBinding(store, resolvedBoundaryPacket)
        val boundaryPacketBeforeProcess = normalizeExecutionBoundaryPacketForRuntime(
            boundaryPacket = boundaryPacketWithCapability,
            store = store,
            availableCapabilities = capabilityCatalog.listCapabilities()
        )
        val boundaryPacketWithProcess = resolveExecutionBoundaryPacketWithReadyProcesses(
            store = store,
            boundaryPacket = boundaryPacketBeforeProcess
        )
        val preparedBoundaryPacket = normalizeExecutionBoundaryPacketForRuntime(
            boundaryPacket = boundaryPacketWithProcess,
            store = store,
            availableCapabilities = capabilityCatalog.listCapabilities()
        )
        val boundTaskRecord = bindPreparedExecutionTaskRecord(
            store = store,
            currentTaskRecord = currentTaskRecord,
            preparedBoundaryPacket = preparedBoundaryPacket
        )
        val taskAuthorityBoundaryPacket = boundTaskRecord.toTaskExecutionBoundaryPacket(
            verificationChecks = preparedBoundaryPacket.verificationChecks
        )
        val processReuseContext = resolvePreparedProcessReuseContextForStart(
            store = store,
            boundaryPacket = taskAuthorityBoundaryPacket
        )
        val guidanceLayer = ProcessReuseRuntime.buildGuidanceLayer(
            taskIntent = processReuseContext?.taskIntent,
            boundaryPacket = taskAuthorityBoundaryPacket
        )
        val routeEcho = resolveExecutionRouteEcho(
            store = store,
            boundaryPacket = taskAuthorityBoundaryPacket,
            guidanceLayer = guidanceLayer,
            processReuseContext = processReuseContext
        )
        val launchDirective = resolveExecutionLaunchDirective(
            hasScreenCapturePermission = hasScreenCapturePermission(),
            requiresScreenCapturePermission = requiresScreenCapturePermissionForExecutionStart(
                store = store,
                boundaryPacket = taskAuthorityBoundaryPacket
            )
        )
        return PreparedExecutionStart(
            taskId = taskAuthorityBoundaryPacket.taskId,
            taskUpdatedAt = taskAuthorityBoundaryPacket.taskUpdatedAt,
            verificationChecks = taskAuthorityBoundaryPacket.verificationChecks,
            processReuseContext = processReuseContext,
            guidanceLayer = guidanceLayer,
            launchDirective = launchDirective,
            startSummary = routeEcho.startSummary,
            routeInfo = routeEcho.routeInfo,
            routeEntryType = routeEcho.routeEntryType
        )
    }

    private fun bindPreparedExecutionTaskRecord(
        store: PrototypeStoreData,
        currentTaskRecord: TaskRecord,
        preparedBoundaryPacket: TaskExecutionBoundaryPacket
    ): TaskRecord {
        val bindingChanged = currentTaskRecord.capabilityStack != preparedBoundaryPacket.capabilityStack ||
            currentTaskRecord.capabilityDomain != preparedBoundaryPacket.capabilityDomain ||
            currentTaskRecord.capabilityId != preparedBoundaryPacket.capabilityId ||
            currentTaskRecord.processId != preparedBoundaryPacket.processId
        if (!bindingChanged) {
            return currentTaskRecord
        }
        val boundTaskRecord = currentTaskRecord.copy(
            capabilityStack = preparedBoundaryPacket.capabilityStack ?: currentTaskRecord.capabilityStack,
            capabilityDomain = preparedBoundaryPacket.capabilityDomain ?: currentTaskRecord.capabilityDomain,
            capabilityId = preparedBoundaryPacket.capabilityId,
            processId = preparedBoundaryPacket.processId,
            updatedAt = System.currentTimeMillis()
        )
        store.updateCurrentIntentSlice(
            currentState = store.resolveCurrentState().copy(currentTaskRecord = boundTaskRecord)
        )
        return boundTaskRecord
    }

    private suspend fun resolveExecutionCapabilityBinding(
        store: PrototypeStoreData,
        boundaryPacket: TaskExecutionBoundaryPacket
    ): TaskExecutionBoundaryPacket {
        val normalizedBoundaryPacket = normalizeExecutionBoundaryPacketForRuntime(
            boundaryPacket = boundaryPacket,
            store = store,
            availableCapabilities = capabilityCatalog.listCapabilities()
        )
        val resolvedCapability = resolveExecutionCapability(store, normalizedBoundaryPacket)
        val capabilityId = resolvedCapability?.capabilityId
            ?: normalizedBoundaryPacket.capabilityId
        if (capabilityId == normalizedBoundaryPacket.capabilityId) {
            return normalizedBoundaryPacket
        }
        return normalizedBoundaryPacket.copy(capabilityId = capabilityId)
    }

    private suspend fun resolveExecutionCapability(
        store: PrototypeStoreData,
        boundaryPacket: TaskExecutionBoundaryPacket
    ): ToolCapability? {
        boundaryPacket.capabilityId?.let { capabilityId ->
            capabilityCatalog.findCapabilityById(capabilityId)?.let { capability ->
                return capability
            }
        }

        val taskQuery = buildExecutionToolQuery(store, boundaryPacket)
        capabilityCatalog.resolveDefaultCapabilityForTask(taskQuery)?.let { capability ->
            return capability
        }

        capabilityCatalog.refreshFromDevice()
        boundaryPacket.capabilityId?.let { capabilityId ->
            capabilityCatalog.findCapabilityById(capabilityId)?.let { capability ->
                return capability
            }
        }
        return capabilityCatalog.resolveDefaultCapabilityForTask(taskQuery)
    }
}

private fun resolveExecutionRouteEcho(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket,
    guidanceLayer: ProcessGuidanceLayer?,
    processReuseContext: CandidateProcessReferenceContext?
): ProcessExplorationRouteEcho {
    val readyProcessAsset = resolveReadyProcessAsset(store, boundaryPacket)
    if (readyProcessAsset != null) {
        val recordName = findCanonicalExecutionRecordName(
            store = store,
            appScope = readyProcessAsset.appScope,
            processAction = readyProcessAsset.resolvedProcessAction(),
            pathIndex = readyProcessAsset.pathIndex
        )
        val readyLabel = recordName ?: readyProcessAsset.processId
        return ProcessExplorationRouteEcho(
            startSummary = com.atombits.pocopaw.UiStrings.resolve(
                com.atombits.pocopaw.R.string.execution_start_process_reference,
                "Execution engine started with reusable process %1\$s.",
                readyLabel
            ),
            routeInfo = buildExecutionRouteInfo(
                store = store,
                guidanceLayer = guidanceLayer,
                processReuseContext = processReuseContext,
                "route=process_reference",
                "route_process=${readyProcessAsset.processId}",
                "route_record=${recordName ?: "-"}",
                "route_path=path${normalizeExecutionRoutePathIndex(readyProcessAsset.pathIndex)}",
                "route_version=v${readyProcessAsset.version.coerceAtLeast(1)}"
            ),
            routeEntryType = ExecutionRouteEntryType.PROCESS_REFERENCE
        )
    }

    val shortcutCandidate = resolveProcessShortcutCandidate(store, boundaryPacket)
    if (shortcutCandidate != null) {
        val recordName = findCanonicalExecutionRecordName(
            store = store,
            appScope = shortcutCandidate.appScope,
            processAction = shortcutCandidate.resolvedProcessAction(),
            pathIndex = shortcutCandidate.pathIndex
        )
        return ProcessExplorationRouteEcho(
            startSummary = com.atombits.pocopaw.UiStrings.resolve(
                com.atombits.pocopaw.R.string.execution_start_shortcut,
                "Execution engine started with reusable shortcut %1\$s.",
                shortcutCandidate.shortcutId
            ),
            routeInfo = buildExecutionRouteInfo(
                store = store,
                guidanceLayer = guidanceLayer,
                processReuseContext = processReuseContext,
                "route=shortcut",
                "shortcut=${shortcutCandidate.shortcutId}",
                "route_process=${shortcutCandidate.processId}",
                "route_record=${recordName ?: "-"}"
            ),
            routeEntryType = ExecutionRouteEntryType.SHORTCUT
        )
    }

    val routedProcessId = boundaryPacket.processId
        ?.takeIf { processId -> processId.isNotBlank() }
        ?: boundaryPacket.actionCode.wireName.takeIf { action -> action.isNotBlank() && action != ActionCode.UNKNOWN.wireName }
        ?: "-"
    val routeEntryType = if (isSystemIntentCapabilityId(boundaryPacket.capabilityId)) {
        ExecutionRouteEntryType.SYSTEM_INTENT
    } else {
        ExecutionRouteEntryType.EXPLORATORY
    }
    return ProcessExplorationRouteEcho(
        startSummary = com.atombits.pocopaw.UiStrings.resolve(
            com.atombits.pocopaw.R.string.execution_start_exploratory,
            "Execution engine started in exploratory mode."
        ),
        routeInfo = buildExecutionRouteInfo(
            store = store,
            guidanceLayer = guidanceLayer,
            processReuseContext = processReuseContext,
            "route=exploratory",
            "route_process=$routedProcessId",
            guidanceLayer?.processScope?.let { processScope -> "route_scope=$processScope" } ?: ""
        ),
        routeEntryType = routeEntryType
    )
}

private fun buildExecutionRouteInfo(
    store: PrototypeStoreData,
    guidanceLayer: ProcessGuidanceLayer?,
    processReuseContext: CandidateProcessReferenceContext?,
    vararg segments: String
): String {
    return (segments.toList() + buildReferenceRouteSegments(store, guidanceLayer, processReuseContext))
        .filter { segment -> segment.isNotBlank() }
        .joinToString(" | ")
}

internal fun buildReferenceRouteSegments(
    store: PrototypeStoreData,
    guidanceLayer: ProcessGuidanceLayer?,
    processReuseContext: CandidateProcessReferenceContext? = store.resolveCurrentProcessReuseContext()
): List<String> {
    val reuseContext = processReuseContext
    return buildList {
        reuseContext?.referenceSummaryLines
            ?.firstOrNull { line ->
                line.startsWith("preferred_candidate=") || line.startsWith("selection=")
            }
            ?.let(::add)
        if (reuseContext?.selectedStageHints.isNullOrEmpty().not()) {
            add("route_stage_hints=${reuseContext?.selectedStageHints.orEmpty().take(3).joinToString(",")}")
        }
        reuseContext?.whySelected
            ?.firstOrNull { value -> value.isNotBlank() }
            ?.let { value -> add("route_why=${sanitizeExecutionRouteValue(value)}") }
        reuseContext?.referenceCautions
            ?.firstOrNull { value -> value.isNotBlank() }
            ?.let { value -> add("route_caution=${sanitizeExecutionRouteValue(value)}") }
        guidanceLayer?.guidanceLines
            ?.firstOrNull { value -> value.isNotBlank() }
            ?.let { value -> add("route_guidance=${sanitizeExecutionRouteValue(value)}") }
    }.distinct()
}

private fun sanitizeExecutionRouteValue(value: String): String {
    return value.replace("|", "/").replace("\n", " ").trim()
}

private fun findCanonicalExecutionRecordName(
    store: PrototypeStoreData,
    appScope: String,
    processAction: String,
    pathIndex: Int?
): String? {
    return findReusableProcessAssetRecordName(
        store = store,
        appScope = appScope,
        processAction = processAction,
        pathIndex = pathIndex
    )
}

private fun normalizeExecutionRoutePathIndex(pathIndex: Int?): Int {
    return pathIndex?.coerceAtLeast(1) ?: 1
}

private fun buildExecutionToolQuery(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket
): String {
    val activeCandidate = store.resolveTaskFirstCandidate()
    val latestUserMessage = store.messages.asReversed().firstOrNull { message ->
        message.role == MessageRole.USER
    }?.content.orEmpty()
    return listOf(
        latestUserMessage,
        boundaryPacket.objectiveSummary,
        boundaryPacket.planSummary,
        activeCandidate?.anchoredLabel.orEmpty(),
        activeCandidate?.action.orEmpty()
    ).filter { value -> value.isNotBlank() }.joinToString("\n")
}