package io.github.simbridge

import android.app.Application
import android.content.Context

class LinkApp : Application() {
    val core by lazy { LinkCore(this) }

    companion object {
        fun core(context: Context): LinkCore = (context.applicationContext as LinkApp).core
    }
}

object Log {
    private const val TAG = "SimBridge"
    fun i(msg: String) { android.util.Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { android.util.Log.w(TAG, msg, t) }
}
