package com.atombits.pocopaw.shizuku

internal fun parseProjectMediaAppOpState(
    stdout: String,
    stderr: String = ""
): MediaProjectionAppOpsState {
    val combined = listOf(stdout, stderr)
        .joinToString(separator = "\n")
        .trim()
        .lowercase()
    return when {
        combined.contains("unknown operation") || combined.contains("unknown op") || combined.contains("not supported") -> {
            MediaProjectionAppOpsState.UNSUPPORTED
        }

        combined.contains("allow") -> MediaProjectionAppOpsState.ALLOWED
        combined.contains("default") -> MediaProjectionAppOpsState.DEFAULT
        combined.contains("deny") || combined.contains("ignore") || combined.contains("errored") -> MediaProjectionAppOpsState.DENIED

        combined.isBlank() -> MediaProjectionAppOpsState.UNKNOWN
        else -> MediaProjectionAppOpsState.UNKNOWN
    }
}

class MediaProjectionAppOpsBootstrapper(
    private val shellCommandRunner: ShizukuShellCommandRunner
) {

    suspend fun ensureAllowed(packageName: String): MediaProjectionAppOpsResult {
        val currentResult = shellCommandRunner.run(ShizukuShellCommand.GetProjectMediaAppOp(packageName))
        val currentState = parseProjectMediaAppOpState(currentResult.stdout, currentResult.stderr)
        if (currentState == MediaProjectionAppOpsState.UNSUPPORTED) {
            return MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.UNSUPPORTED,
                detail = currentResult.stderr.ifBlank { currentResult.stdout }
            )
        }
        if (currentResult.exitCode == 0 && currentState == MediaProjectionAppOpsState.ALLOWED) {
            return MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.ALREADY_ALLOWED
            )
        }

        val setResult = shellCommandRunner.run(ShizukuShellCommand.SetProjectMediaAppOp(packageName))
        val setState = parseProjectMediaAppOpState(setResult.stdout, setResult.stderr)
        if (setState == MediaProjectionAppOpsState.UNSUPPORTED) {
            return MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.UNSUPPORTED,
                detail = setResult.stderr.ifBlank { setResult.stdout }
            )
        }
        if (setResult.exitCode != 0) {
            return MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.WRITE_FAILED,
                detail = setResult.stderr.ifBlank { setResult.stdout }
            )
        }

        val verifyResult = shellCommandRunner.run(ShizukuShellCommand.GetProjectMediaAppOp(packageName))
        val verifyState = parseProjectMediaAppOpState(verifyResult.stdout, verifyResult.stderr)
        return when {
            verifyState == MediaProjectionAppOpsState.UNSUPPORTED -> MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.UNSUPPORTED,
                detail = verifyResult.stderr.ifBlank { verifyResult.stdout }
            )

            verifyResult.exitCode == 0 && verifyState == MediaProjectionAppOpsState.ALLOWED -> MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.ALLOWED
            )

            else -> MediaProjectionAppOpsResult(
                state = MediaProjectionAppOpsState.VERIFY_FAILED,
                detail = verifyResult.stderr.ifBlank { verifyResult.stdout }
            )
        }
    }
}