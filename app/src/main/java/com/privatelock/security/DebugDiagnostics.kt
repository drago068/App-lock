package com.privatelock.security

import android.util.Log
import com.privatelock.BuildConfig
import java.util.Collections
import java.util.IdentityHashMap

/** DEBUG-only stack diagnostics omit exception messages, credentials, package names, and app history. */
object DebugDiagnostics {
    fun lifecycle(component: String, transition: String, taskId: Int, isTaskRoot: Boolean) {
        if (BuildConfig.DEBUG) Log.d("PrivateAppLock", "$component $transition task=$taskId root=$isTaskRoot")
    }

    fun mark(transition: String) {
        if (BuildConfig.DEBUG) Log.d("PrivateAppLock", transition)
    }

    fun timing(phase: String, elapsedMillis: Long) {
        if (BuildConfig.DEBUG) Log.d("PrivateAppLock", "$phase ${elapsedMillis}ms")
    }

    /** Logs a monotonic checkpoint relative to the unlock-button press. */
    fun checkpoint(phase: String, startedAtElapsedRealtime: Long) {
        if (BuildConfig.DEBUG) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAtElapsedRealtime
            Log.d("PrivateAppLock", "$phase +${elapsed}ms")
        }
    }

    fun record(operation: String, failure: Throwable) {
        if (!BuildConfig.DEBUG) return
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val trace = buildString {
            var current: Throwable? = failure
            while (current != null && seen.add(current)) {
                if (isNotEmpty()) append('\n')
                append(current.javaClass.name).append('\n')
                current.stackTrace.forEach { frame ->
                    append("  at ").append(frame.className).append('.').append(frame.methodName)
                        .append('(').append(frame.fileName ?: "Unknown").append(':').append(frame.lineNumber).append(")\n")
                }
                current = current.cause
            }
        }
        Log.e("PrivateAppLock", "$operation failed\n$trace")
    }
}
