package com.atombits.shizukuprobe

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    private val userServiceArgs by lazy(LazyThreadSafetyMode.NONE) {
        Shizuku.UserServiceArgs(
            ComponentName(applicationContext, ProbeUserService::class.java)
        )
            .daemon(false)
            .processNameSuffix("probe")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateStatus("Shizuku binder received")
            maybeBindProbeService(autoTriggered = true)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateStatus("Shizuku binder died")
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE_SHIZUKU_PERMISSION) {
            return@OnRequestPermissionResultListener
        }
        runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                updateStatus("Shizuku permission granted")
                maybeBindProbeService(autoTriggered = true)
            } else {
                updateStatus("Shizuku permission denied")
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            updateStatus("Probe service connected")
            if (service == null) {
                updateStatus("Probe service returned null binder")
                return
            }
            runCatching {
                IProbeUserService.Stub.asInterface(service).ping()
            }.onSuccess { result ->
                updateStatus("Probe ping result: $result")
            }.onFailure { throwable ->
                updateStatus("Probe ping failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                Log.w(TAG, "Probe ping failed", throwable)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            updateStatus("Probe service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        updateStatus("Probe ready")
    }

    override fun onResume() {
        super.onResume()
        maybeBindProbeService(autoTriggered = true)
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        }
        super.onDestroy()
    }

    private fun maybeBindProbeService(autoTriggered: Boolean) {
        when {
            !Shizuku.pingBinder() -> {
                updateStatus("Waiting for Shizuku binder")
            }

            Shizuku.getVersion() < 10 -> {
                updateStatus("Shizuku API 10+ required")
            }

            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                if (autoTriggered) {
                    updateStatus("Requesting Shizuku permission")
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
                } else {
                    updateStatus("Shizuku permission missing")
                }
            }

            else -> {
                updateStatus("Binding probe user service")
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
                }
                runCatching {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                }.onFailure { throwable ->
                    updateStatus("bindUserService failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                    Log.w(TAG, "bindUserService failed", throwable)
                }
            }
        }
    }

    private fun buildContentView(): ScrollView {
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        val bindButton = Button(this).apply {
            text = "Bind Probe Service"
            setOnClickListener {
                maybeBindProbeService(autoTriggered = false)
            }
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 64)
            addView(
                TextView(this@MainActivity).apply {
                    text = "Independent Shizuku user-service probe"
                    textSize = 20f
                    setPadding(0, 0, 0, 24)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                bindButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return ScrollView(this).apply {
            addView(
                rootLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun updateStatus(message: String) {
        statusView.text = message
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "ShizukuProbe"
        const val REQUEST_CODE_SHIZUKU_PERMISSION = 1001
    }
}