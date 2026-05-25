package com.atombits.pocopaw

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun shouldIncludeToolspaceApp(
    packageName: String,
    applicationFlags: Int,
    hasLaunchIntent: Boolean,
    selfPackageName: String
): Boolean {
    if (packageName == selfPackageName || !hasLaunchIntent) {
        return false
    }
    val isSystemApp = (applicationFlags and ApplicationInfo.FLAG_SYSTEM) != 0
    val isUpdatedSystemApp = (applicationFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    return !isSystemApp || isUpdatedSystemApp
}

class ToolspaceCatalogManager(private val context: Context) {

    data class ToolspaceSnapshot(
        val stats: ToolspaceStats,
        val updatedAt: Long?
    )

    data class ToolspaceStats(
        val total: Int,
        val ready: Int,
        val system: Int,
        val app: Int,
        val mcp: Int = 0
    )

    companion object {
        private const val DIR_NAME = "toolspace"
        private const val FILE_NAME = "toolspace_catalog.json"
    }

    private val localSystemScanner = LocalSystemControlScanner(context)
    private val file = File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)
    private val capabilities = mutableMapOf<String, ToolCapability>()
    private var lastUpdatedAt: Long = 0L

    init {
        loadFromDisk()
    }

    fun refreshAll(installedPackages: Set<String>) {
        syncInstalledApps(installedPackages)
        syncSystemControls()
        lastUpdatedAt = System.currentTimeMillis()
        persist()
    }

    fun refreshFromDevice(): ToolspaceSnapshot {
        refreshAll(loadInstalledLaunchablePackages())
        return getSnapshot()
    }

    fun getSnapshot(): ToolspaceSnapshot {
        return ToolspaceSnapshot(
            stats = getStats(),
            updatedAt = lastUpdatedAt.takeIf { timestamp -> timestamp > 0L }
        )
    }

    fun getStats(): ToolspaceStats {
        val values = capabilities.values
        return ToolspaceStats(
            total = values.size,
            ready = values.count { capability -> capability.state == ToolState.READY },
            system = values.count { capability -> capability.domain == ToolDomain.SYSTEM },
            app = values.count { capability -> capability.domain == ToolDomain.APP },
            mcp = values.count { capability -> capability.domain == ToolDomain.MCP }
        )
    }

    fun listCapabilities(): List<ToolCapability> {
        return capabilities.values.sortedBy { capability -> capability.capabilityId }
    }

    fun buildCapabilityBundle(task: String): ToolCapabilityBundle? {
        return ToolCapabilityBundleBuilder.buildForCatalog(task, listCapabilities())
    }

    fun findCapabilityById(capabilityId: String?): ToolCapability? {
        if (capabilityId.isNullOrBlank()) {
            return null
        }
        return capabilities[capabilityId]
    }

    fun resolveDefaultCapabilityForTask(task: String): ToolCapability? {
        return buildCapabilityBundle(task)?.capabilities?.firstOrNull()
    }

    private fun loadInstalledLaunchablePackages(): Set<String> {
        val packageManager = context.packageManager
        val packageInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

        return packageInfos.asSequence()
            .filter { packageInfo ->
                shouldIncludeToolspaceApp(
                    packageName = packageInfo.packageName,
                    applicationFlags = packageInfo.applicationInfo?.flags ?: 0,
                    hasLaunchIntent = packageManager.getLaunchIntentForPackage(packageInfo.packageName) != null,
                    selfPackageName = context.packageName
                )
            }
            .map { packageInfo -> packageInfo.packageName }
            .toSet()
    }

    private fun syncInstalledApps(installedPackages: Set<String>) {
        val packageManager = context.packageManager
        val appCapabilities = installedPackages.sorted().map { packageName ->
            val appName = runCatching {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
                packageManager.getApplicationLabel(appInfo).toString()
            }.getOrDefault(packageName)
            ToolCapability(
                capabilityId = "app.$packageName.open",
                domain = ToolDomain.APP,
                source = packageName,
                invokeUri = packageName,
                risk = ToolRisk.SENSITIVE,
                state = ToolState.READY,
                displayName = appName,
                summary = UiStrings.resolve(
                    R.string.tool_launch_entry_summary,
                    "Launch entry for %1\$s.",
                    appName
                ),
                metadata = mapOf(
                    "displayName" to appName,
                    "appName" to appName,
                    "capabilityType" to "app_entry",
                    "packageName" to packageName
                )
            )
        }

        capabilities.entries.removeIf { (_, capability) -> capability.domain == ToolDomain.APP }
        appCapabilities.forEach { capability ->
            capabilities[capability.capabilityId] = capability
        }
    }

    private fun syncSystemControls() {
        val systemCapabilities = localSystemScanner.scan()
        capabilities.entries.removeIf { (_, capability) -> capability.domain == ToolDomain.SYSTEM }
        systemCapabilities.forEach { capability ->
            capabilities[capability.capabilityId] = capability
        }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject().apply {
                put("updated_at", lastUpdatedAt.takeIf { timestamp -> timestamp > 0 } ?: System.currentTimeMillis())
                put("capabilities", JSONArray(capabilities.values.map { capability -> capability.toJson() }))
            }
            file.writeText(root.toString(2))
        }
    }

    private fun loadFromDisk() {
        if (!file.exists()) {
            return
        }

        runCatching {
            val root = JSONObject(file.readText())
            lastUpdatedAt = root.optLong("updated_at", 0L)
            val capabilityArray = root.optJSONArray("capabilities") ?: JSONArray()
            for (index in 0 until capabilityArray.length()) {
                val capability = ToolCapability.fromJson(capabilityArray.getJSONObject(index))
                capabilities[capability.capabilityId] = capability
            }
        }.onFailure {
            capabilities.clear()
            lastUpdatedAt = 0L
        }
    }
}

internal fun buildTaskCapabilityBundle(
    toolspaceCatalogManager: ToolspaceCatalogManager,
    task: String,
    selectedToolId: String?
): ToolCapabilityBundle? {
    val selectedCapability = toolspaceCatalogManager.findCapabilityById(selectedToolId)
    val baseBundle = toolspaceCatalogManager.buildCapabilityBundle(task)
    if (selectedCapability == null && baseBundle == null) {
        return null
    }

    val capabilities = linkedMapOf<String, ToolCapability>()
    selectedCapability?.let { capability ->
        capabilities[capability.capabilityId] = capability
    }
    baseBundle?.capabilities?.forEach { capability ->
        capabilities[capability.capabilityId] = capability
    }

    return ToolCapabilityBundle(
        selectionMode = baseBundle?.selectionMode ?: "task_context",
        matchedDomains = baseBundle?.matchedDomains ?: emptyList(),
        matchedTerms = baseBundle?.matchedTerms ?: emptyList(),
        capabilities = capabilities.values.toList()
    )
}