package com.atombits.pocopaw
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val SYSTEM_INTENT_SENDTO_SMS = "system.intent.sendto_sms"

private const val SMS_BODY_EXTRA_KEY = "sms_body"

private val sampleExecutionDataFallbackBlockedCapabilities = setOf(
    SYSTEM_INTENT_SENDTO_SMS,
    SYSTEM_INTENT_DIAL,
    SYSTEM_INTENT_CALL
)

internal data class SystemIntentLaunchRequest(
    val action: String,
    val dataUri: String? = null,
    val mimeType: String? = null,
    val stringExtras: Map<String, String> = emptyMap()
)

internal fun TaskExecutionBoundaryPacket.resolveCommunicationMessageBody(): String? {
    return sequenceOf(
        resolvedSlots["communication.message_body"],
        structuredDetailSlots.domain["message_body"]
    ).mapNotNull { value ->
        value?.trim()?.takeIf { candidate -> candidate.isNotBlank() }
    }.firstOrNull()
}

internal fun resolveAppLaunchTarget(
    selectedToolId: String?,
    capability: ToolCapability?
): String? {
    capability?.let { resolvedCapability ->
        if (resolvedCapability.domain == ToolDomain.APP || resolvedCapability.domain == ToolDomain.SYSTEM) {
            return resolvedCapability.invokeUri.ifBlank { resolvedCapability.source }.ifBlank { null }
        }
    }
    val normalizedToolId = selectedToolId?.trim().orEmpty()
    if (normalizedToolId.isBlank()) {
        return null
    }
    return when {
        normalizedToolId.startsWith("system.intent.") -> "sys://$normalizedToolId"
        normalizedToolId.startsWith("app.") && normalizedToolId.endsWith(".open") -> {
            normalizedToolId.removePrefix("app.").removeSuffix(".open").ifBlank { null }
        }

        normalizedToolId.contains('.') -> normalizedToolId
        else -> null
    }
}

internal fun parseSystemIntentLaunchRequest(
    target: String?,
    boundaryPacket: TaskExecutionBoundaryPacket? = null,
    contactResolver: PhoneContactResolver = NoOpPhoneContactResolver
): SystemIntentLaunchRequest? {
    val normalizedTarget = target?.trim().orEmpty()
    if (normalizedTarget.isBlank()) {
        return null
    }
    if (!normalizedTarget.startsWith("sys://", ignoreCase = true)) {
        return null
    }
    val capabilityId = normalizedTarget
        .substringAfter("sys://", "")
        .substringBefore('?')
        .substringBefore('#')
        .trim()
        .ifBlank { null } ?: return null
    val queryParameters = parseSystemIntentQueryParameters(
        normalizedTarget.substringAfter('?', "").substringBefore('#')
    )
    val probe = systemIntentProbes().firstOrNull { probeCandidate ->
        "system.intent.${probeCandidate.capabilitySuffix}" == capabilityId
    } ?: return null
    val dataUri = resolveSystemIntentDataUri(
        queryParameters = queryParameters,
        capabilityId = capabilityId,
        probe = probe,
        boundaryPacket = boundaryPacket,
        contactResolver = contactResolver
    )
    if (probe.requiresDataUri && dataUri.isNullOrBlank()) {
        return null
    }
    val mimeType = resolveSystemIntentMimeType(queryParameters, capabilityId)
    return SystemIntentLaunchRequest(
        action = probe.action,
        dataUri = dataUri,
        mimeType = mimeType,
        stringExtras = resolveRuntimeSystemIntentStringExtras(
            capabilityId = capabilityId,
            boundaryPacket = boundaryPacket
        )
    )
}

internal fun launchCapabilityTarget(
    context: Context,
    target: String?,
    boundaryPacket: TaskExecutionBoundaryPacket? = null
): Boolean {
    parseSystemIntentLaunchRequest(
        target = target,
        boundaryPacket = boundaryPacket,
        contactResolver = AndroidPhoneContactResolver(context.applicationContext)
    )?.let { request ->
        val launchIntent = Intent(request.action).apply {
            if (!request.dataUri.isNullOrBlank()) {
                data = Uri.parse(request.dataUri)
            }
            request.mimeType?.takeIf { value -> value.isNotBlank() }?.let { type = it }
            request.stringExtras.forEach { (key, value) -> putExtra(key, value) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    val normalizedTarget = normalizeBridgeLaunchTarget(target) ?: return false
    if (normalizedTarget.startsWith("http://") || normalizedTarget.startsWith("https://")) {
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(normalizedTarget)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            true
        }.getOrDefault(false)
    }
    return launchAppViaPackageManager(context, normalizedTarget)
}

private fun resolveSystemIntentDataUri(
    queryParameters: Map<String, String>,
    capabilityId: String,
    probe: SystemIntentProbe,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    contactResolver: PhoneContactResolver
): String? {
    listOf("data_uri", "data", "uri", "url", "target")
        .firstNotNullOfOrNull { key -> queryParameters[key]?.takeIf { value -> value.isNotBlank() } }
        ?.let { value -> return value }

    if (capabilityId == "system.intent.application_details_settings") {
        val packageName = listOf("package", "packageName", "package_name")
            .firstNotNullOfOrNull { key -> queryParameters[key]?.takeIf { value -> value.isNotBlank() } }
        if (!packageName.isNullOrBlank()) {
            return "package:$packageName"
        }
    }

    resolveRuntimeSystemIntentDataUri(capabilityId, boundaryPacket, contactResolver)?.let { dataUri ->
        return dataUri
    }

    if (capabilityId in sampleExecutionDataFallbackBlockedCapabilities) {
        return null
    }

    return probe.executeDataUri?.takeIf { value -> value.isNotBlank() }
}

private fun resolveRuntimeSystemIntentDataUri(
    capabilityId: String,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    contactResolver: PhoneContactResolver
): String? {
    val phoneResolution = resolveSystemIntentPhoneRecipient(capabilityId, boundaryPacket, contactResolver)
    return when (capabilityId) {
        SYSTEM_INTENT_SENDTO_SMS -> phoneResolution?.resolvedPhoneNumber?.let { phoneNumber ->
            "smsto:${encodeSystemIntentOpaqueValue(phoneNumber)}"
        }

        SYSTEM_INTENT_DIAL,
        SYSTEM_INTENT_CALL -> phoneResolution?.resolvedPhoneNumber?.let { phoneNumber ->
            "tel:${encodeSystemIntentOpaqueValue(phoneNumber)}"
        }

        else -> null
    }
}

private fun resolveRuntimeSystemIntentStringExtras(
    capabilityId: String,
    boundaryPacket: TaskExecutionBoundaryPacket?
): Map<String, String> {
    val packet = boundaryPacket ?: return emptyMap()
    return when (capabilityId) {
        SYSTEM_INTENT_SENDTO_SMS -> packet.resolveCommunicationMessageBody()?.let { messageBody ->
            mapOf(SMS_BODY_EXTRA_KEY to messageBody)
        } ?: emptyMap()

        else -> emptyMap()
    }
}

private fun encodeSystemIntentOpaqueValue(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}

private fun resolveSystemIntentMimeType(queryParameters: Map<String, String>, capabilityId: String): String? {
    listOf("mime", "mimeType", "type")
        .firstNotNullOfOrNull { key -> queryParameters[key]?.takeIf { value -> value.isNotBlank() } }
        ?.let { value -> return value }

    return when (capabilityId) {
        "system.intent.get_content",
        "system.intent.open_document",
        "system.intent.create_document" -> "*/*"
        else -> null
    }
}

private fun parseSystemIntentQueryParameters(rawQuery: String): Map<String, String> {
    if (rawQuery.isBlank()) {
        return emptyMap()
    }
    return rawQuery.split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=').trim()
            if (key.isBlank()) {
                return@mapNotNull null
            }
            val value = entry.substringAfter('=', "")
            key to decodeSystemIntentQueryComponent(value)
        }
        .toMap()
}

private fun decodeSystemIntentQueryComponent(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}