package com.atombits.pocopaw.shizuku

enum class ShizukuPrivilegeIdentity {
    ROOT,
    SHELL,
    UNKNOWN
}

data class ShizukuStatusSnapshot(
    val installed: Boolean,
    val binderAvailable: Boolean,
    val permissionGranted: Boolean,
    val shouldShowRequestPermissionRationale: Boolean,
    val preV11: Boolean,
    val privilegeIdentity: ShizukuPrivilegeIdentity
) {
    val ready: Boolean
        get() = installed && binderAvailable && permissionGranted && !preV11
}

enum class BootstrapTrigger {
    MANUAL,
    STARTUP,
    CAPTURE_RESULT
}

enum class AccessibilityBootstrapState {
    ALREADY_ENABLED,
    ENABLED,
    ENABLED_PENDING_CONNECTION,
    WRITE_FAILED,
    VERIFY_FAILED
}

data class AccessibilityBootstrapResult(
    val state: AccessibilityBootstrapState,
    val detail: String? = null
)

enum class MediaProjectionAppOpsState {
    ALREADY_ALLOWED,
    ALLOWED,
    DENIED,
    DEFAULT,
    UNSUPPORTED,
    WRITE_FAILED,
    VERIFY_FAILED,
    UNKNOWN
}

data class MediaProjectionAppOpsResult(
    val state: MediaProjectionAppOpsState,
    val detail: String? = null
) {
    val isAllowVerified: Boolean
        get() = state == MediaProjectionAppOpsState.ALREADY_ALLOWED || state == MediaProjectionAppOpsState.ALLOWED
}

enum class ShizukuBootstrapStatusCode(val persistedValue: String) {
    IDLE("idle"),
    DISABLED("disabled"),
    READY("ready"),
    SHIZUKU_UNAVAILABLE("shizuku_unavailable"),
    SHIZUKU_BINDER_UNAVAILABLE("shizuku_binder_unavailable"),
    SHIZUKU_PERMISSION_REQUIRED("permission_required"),
    SHIZUKU_PERMISSION_DENIED("permission_denied"),
    ACCESSIBILITY_ENABLED_PENDING_CONNECTION("accessibility_enabled_pending_connection"),
    ACCESSIBILITY_WRITE_FAILED("accessibility_write_failed"),
    APPOPS_UNSUPPORTED("appops_unsupported"),
    APPOPS_WRITE_FAILED("appops_write_failed"),
    APPOPS_VERIFY_FAILED("appops_verify_failed"),
    CAPTURE_REQUEST_LAUNCHED("capture_request_launched"),
    CAPTURE_CONSENT_REQUIRED_OR_DENIED("capture_consent_required_or_denied");

    companion object {
        fun fromPersistedValue(value: String?): ShizukuBootstrapStatusCode {
            return values().firstOrNull { it.persistedValue == value } ?: IDLE
        }
    }
}

data class ShizukuBootstrapStatus(
    val code: ShizukuBootstrapStatusCode,
    val trigger: BootstrapTrigger,
    val probe: ShizukuStatusSnapshot,
    val accessibility: AccessibilityBootstrapResult? = null,
    val mediaProjectionAppOps: MediaProjectionAppOpsResult? = null,
    val detail: String? = null,
    val captureReady: Boolean = false
)

data class ShizukuBootstrapPlan(
    val status: ShizukuBootstrapStatus,
    val requestPermission: Boolean = false,
    val launchCapture: Boolean = false
)