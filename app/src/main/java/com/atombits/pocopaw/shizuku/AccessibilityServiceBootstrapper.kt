package com.atombits.pocopaw.shizuku

import android.content.ComponentName
import android.content.Context
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun mergeEnabledAccessibilityServices(
    existingValue: String?,
    componentName: String
): String {
    val normalizedEntries = existingValue.orEmpty()
        .split(':')
        .map { entry -> entry.trim() }
        .filter { entry -> entry.isNotEmpty() }
        .toMutableList()
    val containsComponent = normalizedEntries.any { entry ->
        entry.equals(componentName, ignoreCase = true)
    }
    if (!containsComponent) {
        normalizedEntries += componentName
    }
    return normalizedEntries.joinToString(separator = ":")
}

internal fun containsEnabledAccessibilityService(
    existingValue: String?,
    componentName: String
): Boolean {
    return existingValue.orEmpty()
        .split(':')
        .map { entry -> entry.trim() }
        .filter { entry -> entry.isNotEmpty() }
        .any { entry -> entry.equals(componentName, ignoreCase = true) }
}

class AccessibilityServiceBootstrapper(
    context: Context,
    private val shellCommandRunner: ShizukuShellCommandRunner
) {
    private val serviceComponent = ComponentName(
        context.applicationContext,
        PrototypeAccessibilityService::class.java
    ).flattenToString()

    suspend fun ensureEnabled(): AccessibilityBootstrapResult {
        val servicesResult = shellCommandRunner.run(ShizukuShellCommand.GetEnabledAccessibilityServices)
        if (servicesResult.exitCode != 0) {
            return AccessibilityBootstrapResult(
                state = AccessibilityBootstrapState.WRITE_FAILED,
                detail = servicesResult.stderr.ifBlank { servicesResult.stdout }
            )
        }

        val accessibilityEnabledResult = shellCommandRunner.run(ShizukuShellCommand.GetAccessibilityEnabled)
        if (accessibilityEnabledResult.exitCode != 0) {
            return AccessibilityBootstrapResult(
                state = AccessibilityBootstrapState.WRITE_FAILED,
                detail = accessibilityEnabledResult.stderr.ifBlank { accessibilityEnabledResult.stdout }
            )
        }

        val mergedServices = mergeEnabledAccessibilityServices(servicesResult.stdout, serviceComponent)
        val alreadyEnabled = containsEnabledAccessibilityService(servicesResult.stdout, serviceComponent) &&
            accessibilityEnabledResult.stdout.trim() == "1"
        if (!alreadyEnabled) {
            val putServicesResult = shellCommandRunner.run(
                ShizukuShellCommand.PutEnabledAccessibilityServices(mergedServices)
            )
            if (putServicesResult.exitCode != 0) {
                return AccessibilityBootstrapResult(
                    state = AccessibilityBootstrapState.WRITE_FAILED,
                    detail = putServicesResult.stderr.ifBlank { putServicesResult.stdout }
                )
            }

            val putEnabledResult = shellCommandRunner.run(
                ShizukuShellCommand.PutAccessibilityEnabled(enabled = true)
            )
            if (putEnabledResult.exitCode != 0) {
                return AccessibilityBootstrapResult(
                    state = AccessibilityBootstrapState.WRITE_FAILED,
                    detail = putEnabledResult.stderr.ifBlank { putEnabledResult.stdout }
                )
            }
        }

        val verifyServicesResult = shellCommandRunner.run(ShizukuShellCommand.GetEnabledAccessibilityServices)
        val verifyEnabledResult = shellCommandRunner.run(ShizukuShellCommand.GetAccessibilityEnabled)
        val verified = verifyServicesResult.exitCode == 0 &&
            verifyEnabledResult.exitCode == 0 &&
            containsEnabledAccessibilityService(verifyServicesResult.stdout, serviceComponent) &&
            verifyEnabledResult.stdout.trim() == "1"
        if (!verified) {
            return AccessibilityBootstrapResult(
                state = AccessibilityBootstrapState.VERIFY_FAILED,
                detail = listOf(verifyServicesResult.stderr, verifyEnabledResult.stderr)
                    .firstOrNull { it.isNotBlank() }
            )
        }

        val serviceConnected = waitForAccessibilityConnection()
        return AccessibilityBootstrapResult(
            state = when {
                PrototypeAccessibilityService.instance != null && alreadyEnabled -> AccessibilityBootstrapState.ALREADY_ENABLED
                serviceConnected -> AccessibilityBootstrapState.ENABLED
                else -> AccessibilityBootstrapState.ENABLED_PENDING_CONNECTION
            }
        )
    }

    private suspend fun waitForAccessibilityConnection(timeoutMs: Long = 1500L): Boolean {
        return withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (PrototypeAccessibilityService.instance != null) {
                    return@withContext true
                }
                delay(100L)
            }
            PrototypeAccessibilityService.instance != null
        }
    }
}