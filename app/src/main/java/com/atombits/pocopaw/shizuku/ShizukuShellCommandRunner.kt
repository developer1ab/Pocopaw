package com.atombits.pocopaw.shizuku

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ShizukuCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
)

sealed class ShizukuShellCommand {
    abstract fun args(): Array<String>

    object GetEnabledAccessibilityServices : ShizukuShellCommand() {
        override fun args(): Array<String> {
            return arrayOf("settings", "get", "secure", "enabled_accessibility_services")
        }
    }

    object GetAccessibilityEnabled : ShizukuShellCommand() {
        override fun args(): Array<String> {
            return arrayOf("settings", "get", "secure", "accessibility_enabled")
        }
    }

    data class PutEnabledAccessibilityServices(private val services: String) : ShizukuShellCommand() {
        override fun args(): Array<String> {
            return arrayOf("settings", "put", "secure", "enabled_accessibility_services", services)
        }
    }

    data class PutAccessibilityEnabled(private val enabled: Boolean) : ShizukuShellCommand() {
        override fun args(): Array<String> {
            return arrayOf("settings", "put", "secure", "accessibility_enabled", if (enabled) "1" else "0")
        }
    }

    data class GetProjectMediaAppOp(private val packageName: String) : ShizukuShellCommand() {
        override fun args(): Array<String> {
            return arrayOf("appops", "get", packageName, "PROJECT_MEDIA")
        }
    }

    data class SetProjectMediaAppOp(private val packageName: String) : ShizukuShellCommand() {
        override fun args(): Array<String> {
            return arrayOf("appops", "set", packageName, "PROJECT_MEDIA", "allow")
        }
    }
}

internal const val USER_SERVICE_RESULT_EXIT_CODE = "exitCode"
internal const val USER_SERVICE_RESULT_STDOUT = "stdout"
internal const val USER_SERVICE_RESULT_STDERR = "stderr"
internal const val USER_SERVICE_RESULT_DURATION_MS = "durationMs"

private const val SHIZUKU_COMMAND_TIMEOUT_MS = 15000L
private const val SHELL_COMMAND_TRANSACTION = ('_'.code shl 24) or ('C'.code shl 16) or ('M'.code shl 8) or 'D'.code

class ShizukuShellCommandRunner(@Suppress("UNUSED_PARAMETER") context: Context) {

    suspend fun run(command: ShizukuShellCommand): ShizukuCommandResult = withContext(Dispatchers.IO) {
        executeCommand(command)
    }

    private suspend fun executeCommand(command: ShizukuShellCommand): ShizukuCommandResult {
        if (Shizuku.getVersion() < 10) {
            return ShizukuCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku direct shell requires API 10+",
                durationMs = 0L
            )
        }

        val fullArgs = command.args()
        val serviceName = fullArgs.firstOrNull()
            ?: return ShizukuCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Empty Shizuku command",
                durationMs = 0L
            )
        val serviceArgs = fullArgs.copyOfRange(1, fullArgs.size)
        val rawService = SystemServiceHelper.getSystemService(serviceName)
            ?: return ShizukuCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "System service unavailable: $serviceName",
                durationMs = 0L
            )
        val startedAt = System.currentTimeMillis()
        val privilegedService = ShizukuBinderWrapper(rawService)
        val stdinPipe = ParcelFileDescriptor.createPipe()
        val stdoutPipe = ParcelFileDescriptor.createPipe()
        val stderrPipe = ParcelFileDescriptor.createPipe()
        closeQuietly(stdinPipe[1])

        try {
            return coroutineScope {
                val stdoutReader = async(Dispatchers.IO) {
                    readPipe(stdoutPipe[0])
                }
                val stderrReader = async(Dispatchers.IO) {
                    readPipe(stderrPipe[0])
                }
                val exitCode = withTimeout(SHIZUKU_COMMAND_TIMEOUT_MS) {
                    runShellCommandTransaction(
                        binder = privilegedService,
                        args = serviceArgs,
                        stdinRead = stdinPipe[0],
                        stdoutWrite = stdoutPipe[1],
                        stderrWrite = stderrPipe[1]
                    )
                }
                ShizukuCommandResult(
                    exitCode = exitCode,
                    stdout = stdoutReader.await().trim(),
                    stderr = stderrReader.await().trim(),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            }
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Shizuku command failed: ${fullArgs.joinToString(" ")}",
                throwable
            )
        } finally {
            closeQuietly(stdinPipe[0])
            closeQuietly(stdinPipe[1])
            closeQuietly(stdoutPipe[0])
            closeQuietly(stdoutPipe[1])
            closeQuietly(stderrPipe[0])
            closeQuietly(stderrPipe[1])
        }
    }

    private suspend fun runShellCommandTransaction(
        binder: IBinder,
        args: Array<String>,
        stdinRead: ParcelFileDescriptor,
        stdoutWrite: ParcelFileDescriptor,
        stderrWrite: ParcelFileDescriptor
    ): Int = suspendCancellableCoroutine { continuation ->
        val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (continuation.isActive) {
                    continuation.resume(resultCode)
                }
            }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        continuation.invokeOnCancellation {
            closeQuietly(stdinRead)
            closeQuietly(stdoutWrite)
            closeQuietly(stderrWrite)
        }

        try {
            data.writeFileDescriptor(stdinRead.fileDescriptor)
            data.writeFileDescriptor(stdoutWrite.fileDescriptor)
            data.writeFileDescriptor(stderrWrite.fileDescriptor)
            data.writeStringArray(args)
            data.writeStrongBinder(null)
            resultReceiver.writeToParcel(data, 0)

            val transactionHandled = binder.transact(SHELL_COMMAND_TRANSACTION, data, reply, 0)
            if (!transactionHandled) {
                throw IllegalStateException("System service rejected shell command transaction")
            }
            reply.readException()
            closeQuietly(stdinRead)
            closeQuietly(stdoutWrite)
            closeQuietly(stderrWrite)
        } catch (throwable: Throwable) {
            closeQuietly(stdinRead)
            closeQuietly(stdoutWrite)
            closeQuietly(stderrWrite)
            if (continuation.isActive) {
                continuation.resumeWithException(throwable)
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun readPipe(pipe: ParcelFileDescriptor): String {
        return ParcelFileDescriptor.AutoCloseInputStream(pipe)
            .bufferedReader()
            .use { reader ->
                reader.readText()
            }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        runCatching {
            descriptor.close()
        }
    }
}