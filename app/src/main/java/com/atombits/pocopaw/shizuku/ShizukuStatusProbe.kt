package com.atombits.pocopaw.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
private const val SHELL_UID = 2000

class ShizukuStatusProbe(private val context: Context) {

    fun probe(): ShizukuStatusSnapshot {
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val managerInstalled = isPackageInstalled(SHIZUKU_MANAGER_PACKAGE)
        val installed = managerInstalled || binderAvailable
        val preV11 = if (binderAvailable) {
            runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        } else {
            false
        }
        val permissionGranted = if (binderAvailable && !preV11) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }
        val shouldShowRequestPermissionRationale = if (binderAvailable && !permissionGranted && !preV11) {
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        } else {
            false
        }
        val privilegeIdentity = if (binderAvailable && permissionGranted && !preV11) {
            when (runCatching { Shizuku.getUid() }.getOrDefault(-1)) {
                0 -> ShizukuPrivilegeIdentity.ROOT
                SHELL_UID -> ShizukuPrivilegeIdentity.SHELL
                else -> ShizukuPrivilegeIdentity.UNKNOWN
            }
        } else {
            ShizukuPrivilegeIdentity.UNKNOWN
        }
        return ShizukuStatusSnapshot(
            installed = installed,
            binderAvailable = binderAvailable,
            permissionGranted = permissionGranted,
            shouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale,
            preV11 = preV11,
            privilegeIdentity = privilegeIdentity
        )
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}