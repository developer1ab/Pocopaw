package com.atombits.pocopaw.shizuku

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep

class ShizukuCommandUserService() : IShizukuCommandService.Stub() {

    @Keep
    constructor(context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun runCommand(command: Array<out String>?): Bundle {
        val startedAt = System.currentTimeMillis()
        if (command.isNullOrEmpty()) {
            return Bundle().apply {
                putInt(USER_SERVICE_RESULT_EXIT_CODE, -1)
                putString(USER_SERVICE_RESULT_STDERR, "Empty command")
                putLong(USER_SERVICE_RESULT_DURATION_MS, 0L)
            }
        }

        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { reader ->
                reader.readText().trim()
            }
            val stderr = process.errorStream.bufferedReader().use { reader ->
                reader.readText().trim()
            }
            val exitCode = process.waitFor()
            Bundle().apply {
                putInt(USER_SERVICE_RESULT_EXIT_CODE, exitCode)
                putString(USER_SERVICE_RESULT_STDOUT, stdout)
                putString(USER_SERVICE_RESULT_STDERR, stderr)
                putLong(USER_SERVICE_RESULT_DURATION_MS, System.currentTimeMillis() - startedAt)
            }
        } catch (throwable: Throwable) {
            Bundle().apply {
                putInt(USER_SERVICE_RESULT_EXIT_CODE, -1)
                putString(USER_SERVICE_RESULT_STDOUT, "")
                putString(USER_SERVICE_RESULT_STDERR, throwable.message ?: throwable.javaClass.simpleName)
                putLong(USER_SERVICE_RESULT_DURATION_MS, System.currentTimeMillis() - startedAt)
            }
        }
    }
}