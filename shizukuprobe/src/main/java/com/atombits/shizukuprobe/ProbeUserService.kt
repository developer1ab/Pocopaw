package com.atombits.shizukuprobe

import android.content.Context
import android.util.Log

class ProbeUserService() : IProbeUserService.Stub() {

    constructor(context: Context) : this() {
        Log.i(TAG, "ProbeUserService context constructor invoked: $context")
    }

    init {
        Log.i(TAG, "ProbeUserService initialized")
    }

    override fun destroy() {
        Log.i(TAG, "ProbeUserService destroy")
        System.exit(0)
    }

    override fun ping(): String {
        Log.i(TAG, "ProbeUserService ping")
        return "probe-ok"
    }

    private companion object {
        const val TAG = "ShizukuProbe"
    }
}