package com.atombits.pocopaw

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings

internal data class SystemIntentProbe(
    val capabilitySuffix: String,
    val action: String,
    val probeDataUri: String? = null,
    val executeDataUri: String? = null,
    val requiresDataUri: Boolean = false,
    val risk: ToolRisk,
    val displayNameOverride: String? = null,
    val summaryOverride: String? = null
)

internal fun systemIntentProbes(): List<SystemIntentProbe> {
    return listOf(
        SystemIntentProbe("settings", Settings.ACTION_SETTINGS, risk = ToolRisk.SAFE),
        SystemIntentProbe("application_settings", Settings.ACTION_APPLICATION_SETTINGS, risk = ToolRisk.SAFE),
        SystemIntentProbe("wifi_settings", Settings.ACTION_WIFI_SETTINGS, risk = ToolRisk.SAFE),
        SystemIntentProbe("bluetooth_settings", Settings.ACTION_BLUETOOTH_SETTINGS, risk = ToolRisk.SAFE),
        SystemIntentProbe("location_source_settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("manage_all_applications_settings", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("sync_settings", Settings.ACTION_SYNC_SETTINGS, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("airplane_mode_settings", Settings.ACTION_AIRPLANE_MODE_SETTINGS, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe(
            "application_details_settings",
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            probeDataUri = "package:com.android.settings",
            executeDataUri = "package:com.android.settings",
            requiresDataUri = true,
            risk = ToolRisk.SENSITIVE
        ),
        SystemIntentProbe("set_alarm", AlarmClock.ACTION_SET_ALARM, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("set_timer", AlarmClock.ACTION_SET_TIMER, risk = ToolRisk.SAFE),
        SystemIntentProbe("show_alarms", AlarmClock.ACTION_SHOW_ALARMS, risk = ToolRisk.SAFE),
        SystemIntentProbe(
            "calendar_insert",
            Intent.ACTION_INSERT,
            probeDataUri = "content://com.android.calendar/events",
            executeDataUri = "content://com.android.calendar/events",
            requiresDataUri = true,
            risk = ToolRisk.SENSITIVE
        ),
        SystemIntentProbe(
            "contact_pick",
            Intent.ACTION_PICK,
            probeDataUri = "content://com.android.contacts/contacts",
            executeDataUri = "content://com.android.contacts/contacts",
            requiresDataUri = true,
            risk = ToolRisk.SENSITIVE
        ),
        SystemIntentProbe("image_capture", "android.media.action.IMAGE_CAPTURE", risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("video_capture", "android.media.action.VIDEO_CAPTURE", risk = ToolRisk.SENSITIVE),
        SystemIntentProbe(
            "dial",
            Intent.ACTION_DIAL,
            probeDataUri = "tel:10086",
            executeDataUri = "tel:10086",
            requiresDataUri = true,
            risk = ToolRisk.SAFE
        ),
        SystemIntentProbe(
            "call",
            Intent.ACTION_CALL,
            probeDataUri = "tel:10086",
            executeDataUri = "tel:10086",
            requiresDataUri = true,
            risk = ToolRisk.RESTRICTED
        ),
        SystemIntentProbe(
            "sendto_sms",
            Intent.ACTION_SENDTO,
            probeDataUri = "smsto:10086",
            executeDataUri = "smsto:10086",
            requiresDataUri = true,
            risk = ToolRisk.SENSITIVE
        ),
        SystemIntentProbe(
            "sendto_mail",
            Intent.ACTION_SENDTO,
            probeDataUri = "mailto:test@example.com",
            executeDataUri = "mailto:test@example.com",
            requiresDataUri = true,
            risk = ToolRisk.SENSITIVE
        ),
        SystemIntentProbe(
            "view",
            Intent.ACTION_VIEW,
            probeDataUri = "https://www.android.com",
            executeDataUri = "https://www.android.com",
            requiresDataUri = true,
            risk = ToolRisk.SAFE,
            displayNameOverride = "Open Link",
            summaryOverride = "System route for opening links."
        ),
        SystemIntentProbe(
            "map_geo",
            Intent.ACTION_VIEW,
            probeDataUri = "geo:39.9042,116.4074",
            executeDataUri = "geo:39.9042,116.4074",
            requiresDataUri = true,
            risk = ToolRisk.SAFE
        ),
        SystemIntentProbe(
            "package_delete",
            Intent.ACTION_DELETE,
            probeDataUri = "package:com.android.settings",
            executeDataUri = "package:com.android.settings",
            requiresDataUri = true,
            risk = ToolRisk.RESTRICTED
        ),
        SystemIntentProbe(
            "package_install",
            "android.intent.action.INSTALL_PACKAGE",
            probeDataUri = "content://com.android.externalstorage.documents/document/primary:Download/test.apk",
            requiresDataUri = true,
            risk = ToolRisk.RESTRICTED
        ),
        SystemIntentProbe("get_content", Intent.ACTION_GET_CONTENT, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("open_document", Intent.ACTION_OPEN_DOCUMENT, risk = ToolRisk.SENSITIVE),
        SystemIntentProbe("create_document", Intent.ACTION_CREATE_DOCUMENT, risk = ToolRisk.SENSITIVE)
    )
}

class LocalSystemControlScanner(private val context: Context) {

    fun scan(): List<ToolCapability> {
        val packageManager = context.packageManager
        return systemIntentProbes().map { probe ->
            val handlers = resolveHandlerCount(packageManager, probe.action, probe.probeDataUri)
            val state = when {
                handlers <= 0 -> ToolState.REJECTED
                probe.requiresDataUri && probe.executeDataUri.isNullOrBlank() -> ToolState.NEEDS_ENRICHMENT
                else -> ToolState.READY
            }
            val capabilityId = "system.intent.${probe.capabilitySuffix}"
            val displayName = probe.displayNameOverride ?: humanizeCapabilityName(probe.capabilitySuffix)
            ToolCapability(
                capabilityId = capabilityId,
                domain = ToolDomain.SYSTEM,
                source = "android.system",
                invokeUri = "sys://$capabilityId",
                risk = probe.risk,
                state = state,
                displayName = displayName,
                summary = probe.summaryOverride ?: "System route for $displayName.",
                metadata = mapOf(
                    "displayName" to displayName,
                    "intentAction" to probe.action,
                    "handlerCount" to handlers.toString(),
                    "requiresDataUri" to probe.requiresDataUri.toString(),
                    "probeDataUri" to (probe.probeDataUri ?: ""),
                    "executeDataUri" to (probe.executeDataUri ?: "")
                )
            )
        }
    }

    private fun resolveHandlerCount(packageManager: PackageManager, action: String, dataUri: String?): Int {
        val intent = Intent(action).apply {
            if (!dataUri.isNullOrBlank()) {
                data = Uri.parse(dataUri)
            }
        }
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).size
    }

    private fun humanizeCapabilityName(raw: String): String {
        return raw.split('_')
            .filter { token -> token.isNotBlank() }
            .joinToString(" ") { token -> token.replaceFirstChar { char -> char.uppercase() } }
    }
}