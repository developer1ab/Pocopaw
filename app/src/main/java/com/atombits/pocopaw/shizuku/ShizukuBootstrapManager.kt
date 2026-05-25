package com.atombits.pocopaw.shizuku

import android.content.Context
import com.atombits.pocopaw.service.CaptureService
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ShizukuBootstrapManager(
    context: Context,
    private val settingsStore: ShizukuBootstrapSettingsStore = ShizukuBootstrapSettingsStore(context),
    private val statusProbe: ShizukuStatusProbe = ShizukuStatusProbe(context),
    private val shellCommandRunner: ShizukuShellCommandRunner = ShizukuShellCommandRunner(context),
    private val accessibilityBootstrapper: AccessibilityServiceBootstrapper = AccessibilityServiceBootstrapper(
        context = context,
        shellCommandRunner = shellCommandRunner
    ),
    private val mediaProjectionAppOpsBootstrapper: MediaProjectionAppOpsBootstrapper = MediaProjectionAppOpsBootstrapper(
        shellCommandRunner = shellCommandRunner
    ),
    private val captureReadyProvider: () -> Boolean = { CaptureService.isReady }
) {
    private val appContext = context.applicationContext

    fun currentStatusSnapshot(): ShizukuStatusSnapshot {
        return statusProbe.probe()
    }

    fun isAutoBootstrapEnabled(): Boolean {
        return settingsStore.isAutoBootstrapEnabled()
    }

    fun setAutoBootstrapEnabled(enabled: Boolean): Boolean {
        return settingsStore.writeAutoBootstrapEnabled(enabled)
    }

    fun readLastBootstrapStatusCode(): ShizukuBootstrapStatusCode {
        return settingsStore.readLastBootstrapStatusCode()
    }

    fun readLastBootstrapAttemptAt(): Long? {
        return settingsStore.readLastBootstrapAttemptAt()
    }

    suspend fun prepareManually(): ShizukuBootstrapPlan {
        return prepare(trigger = BootstrapTrigger.MANUAL, allowPermissionRequest = true)
    }

    suspend fun continueAfterPermissionGrant(trigger: BootstrapTrigger = BootstrapTrigger.MANUAL): ShizukuBootstrapPlan {
        return executeBootstrap(trigger = trigger)
    }

    suspend fun prepareOnStartup(): ShizukuBootstrapPlan {
        if (!settingsStore.isAutoBootstrapEnabled()) {
            return persist(
                ShizukuBootstrapPlan(
                    status = ShizukuBootstrapStatus(
                        code = ShizukuBootstrapStatusCode.DISABLED,
                        trigger = BootstrapTrigger.STARTUP,
                        probe = statusProbe.probe()
                    )
                )
            )
        }
        return prepare(trigger = BootstrapTrigger.STARTUP, allowPermissionRequest = false)
    }

    suspend fun onCapturePermissionGranted(): ShizukuBootstrapStatus {
        val captureReady = waitForCaptureReady()
        val probe = statusProbe.probe()
        val accessibilityState = if (PrototypeAccessibilityService.instance != null) {
            AccessibilityBootstrapState.ENABLED
        } else {
            AccessibilityBootstrapState.ENABLED_PENDING_CONNECTION
        }
        val status = ShizukuBootstrapStatus(
            code = if (captureReady && accessibilityState == AccessibilityBootstrapState.ENABLED) {
                ShizukuBootstrapStatusCode.READY
            } else if (captureReady) {
                ShizukuBootstrapStatusCode.ACCESSIBILITY_ENABLED_PENDING_CONNECTION
            } else {
                ShizukuBootstrapStatusCode.CAPTURE_CONSENT_REQUIRED_OR_DENIED
            },
            trigger = BootstrapTrigger.CAPTURE_RESULT,
            probe = probe,
            accessibility = AccessibilityBootstrapResult(accessibilityState),
            captureReady = captureReady
        )
        settingsStore.recordBootstrapStatus(status.code)
        return status
    }

    fun onCapturePermissionDenied(): ShizukuBootstrapStatus {
        val status = ShizukuBootstrapStatus(
            code = ShizukuBootstrapStatusCode.CAPTURE_CONSENT_REQUIRED_OR_DENIED,
            trigger = BootstrapTrigger.CAPTURE_RESULT,
            probe = statusProbe.probe(),
            captureReady = false
        )
        settingsStore.recordBootstrapStatus(status.code)
        return status
    }

    fun onPermissionDenied(trigger: BootstrapTrigger = BootstrapTrigger.MANUAL): ShizukuBootstrapStatus {
        val status = ShizukuBootstrapStatus(
            code = ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_DENIED,
            trigger = trigger,
            probe = statusProbe.probe()
        )
        settingsStore.recordBootstrapStatus(status.code)
        return status
    }

    private suspend fun prepare(
        trigger: BootstrapTrigger,
        allowPermissionRequest: Boolean
    ): ShizukuBootstrapPlan {
        val probe = statusProbe.probe()
        if (!probe.installed) {
            return persist(plan(trigger, probe, ShizukuBootstrapStatusCode.SHIZUKU_UNAVAILABLE))
        }
        if (!probe.binderAvailable || probe.preV11) {
            return persist(plan(trigger, probe, ShizukuBootstrapStatusCode.SHIZUKU_BINDER_UNAVAILABLE))
        }
        if (!probe.permissionGranted) {
            return if (allowPermissionRequest && !probe.shouldShowRequestPermissionRationale) {
                persist(
                    ShizukuBootstrapPlan(
                        status = ShizukuBootstrapStatus(
                            code = ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_REQUIRED,
                            trigger = trigger,
                            probe = probe
                        ),
                        requestPermission = true
                    )
                )
            } else {
                persist(plan(trigger, probe, ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_DENIED))
            }
        }
        return executeBootstrap(trigger)
    }

    private suspend fun executeBootstrap(trigger: BootstrapTrigger): ShizukuBootstrapPlan {
        val probe = statusProbe.probe()
        val accessibilityResult = runCatching {
            accessibilityBootstrapper.ensureEnabled()
        }.getOrElse { throwable ->
            return persist(
                ShizukuBootstrapPlan(
                    status = ShizukuBootstrapStatus(
                        code = ShizukuBootstrapStatusCode.ACCESSIBILITY_WRITE_FAILED,
                        trigger = trigger,
                        probe = probe,
                        accessibility = AccessibilityBootstrapResult(
                            state = AccessibilityBootstrapState.WRITE_FAILED,
                            detail = throwable.message
                        ),
                        detail = throwable.message,
                        captureReady = captureReadyProvider()
                    )
                )
            )
        }
        val appOpsResult = runCatching {
            mediaProjectionAppOpsBootstrapper.ensureAllowed(appContext.packageName)
        }.getOrElse { throwable ->
            return persist(
                ShizukuBootstrapPlan(
                    status = ShizukuBootstrapStatus(
                        code = ShizukuBootstrapStatusCode.APPOPS_WRITE_FAILED,
                        trigger = trigger,
                        probe = probe,
                        accessibility = accessibilityResult,
                        mediaProjectionAppOps = MediaProjectionAppOpsResult(
                            state = MediaProjectionAppOpsState.WRITE_FAILED,
                            detail = throwable.message
                        ),
                        detail = throwable.message,
                        captureReady = captureReadyProvider()
                    )
                )
            )
        }
        val captureReady = captureReadyProvider()
        val launchCapture = !captureReady && appOpsResult.isAllowVerified
        val statusCode = when {
            accessibilityResult.state == AccessibilityBootstrapState.WRITE_FAILED ||
                accessibilityResult.state == AccessibilityBootstrapState.VERIFY_FAILED -> {
                ShizukuBootstrapStatusCode.ACCESSIBILITY_WRITE_FAILED
            }

            accessibilityResult.state == AccessibilityBootstrapState.ENABLED_PENDING_CONNECTION -> {
                ShizukuBootstrapStatusCode.ACCESSIBILITY_ENABLED_PENDING_CONNECTION
            }

            appOpsResult.state == MediaProjectionAppOpsState.UNSUPPORTED -> {
                ShizukuBootstrapStatusCode.APPOPS_UNSUPPORTED
            }

            appOpsResult.state == MediaProjectionAppOpsState.WRITE_FAILED -> {
                ShizukuBootstrapStatusCode.APPOPS_WRITE_FAILED
            }

            appOpsResult.state == MediaProjectionAppOpsState.VERIFY_FAILED ||
                appOpsResult.state == MediaProjectionAppOpsState.UNKNOWN -> {
                ShizukuBootstrapStatusCode.APPOPS_VERIFY_FAILED
            }

            captureReady -> ShizukuBootstrapStatusCode.READY
            launchCapture -> ShizukuBootstrapStatusCode.CAPTURE_REQUEST_LAUNCHED
            else -> ShizukuBootstrapStatusCode.READY
        }
        return persist(
            ShizukuBootstrapPlan(
                status = ShizukuBootstrapStatus(
                    code = statusCode,
                    trigger = trigger,
                    probe = probe,
                    accessibility = accessibilityResult,
                    mediaProjectionAppOps = appOpsResult,
                    detail = listOf(accessibilityResult.detail, appOpsResult.detail)
                        .firstOrNull { !it.isNullOrBlank() },
                    captureReady = captureReady
                ),
                launchCapture = launchCapture
            )
        )
    }

    private fun plan(
        trigger: BootstrapTrigger,
        probe: ShizukuStatusSnapshot,
        code: ShizukuBootstrapStatusCode
    ): ShizukuBootstrapPlan {
        return ShizukuBootstrapPlan(
            status = ShizukuBootstrapStatus(
                code = code,
                trigger = trigger,
                probe = probe
            )
        )
    }

    private fun persist(plan: ShizukuBootstrapPlan): ShizukuBootstrapPlan {
        settingsStore.recordBootstrapStatus(plan.status.code)
        return plan
    }

    private suspend fun waitForCaptureReady(timeoutMs: Long = 1500L): Boolean {
        return withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (captureReadyProvider()) {
                    return@withContext true
                }
                delay(100L)
            }
            captureReadyProvider()
        }
    }
}