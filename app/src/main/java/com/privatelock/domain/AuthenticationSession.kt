package com.privatelock.domain

import com.privatelock.security.DebugDiagnostics

/** Process-local authentication state; credentials and history never go to a log or disk. */
object AuthenticationSession {
    @Volatile private var packageName: String? = null
    @Volatile private var authenticatedAt: Long = 0L
    @Volatile private var policy: Policy = Policy.IMMEDIATE
    private var targetArrivalReported = false
    private var targetForegroundSeen = false
    enum class Policy { IMMEDIATE, AFTER_1_MIN, SCREEN_OFF }

    @Synchronized fun authenticate(pkg: String) {
        packageName = pkg
        authenticatedAt = android.os.SystemClock.elapsedRealtime()
        targetArrivalReported = false
        targetForegroundSeen = false
        DebugDiagnostics.mark("authentication_granted")
    }
    @Synchronized fun onForegroundChanged(pkg: String) {
        val authenticatedPackage = packageName ?: return
        if (authenticatedPackage == pkg) {
            targetForegroundSeen = true
            return
        }
        // Task switching can report launcher/system windows before the requested app.
        // Immediate re-lock starts only after the authenticated app has actually appeared.
        if (targetForegroundSeen && policy == Policy.IMMEDIATE) {
            DebugDiagnostics.mark("authentication_cleared_immediate_foreground_change")
            clear()
        }
    }
    fun isAuthenticated(pkg: String): Boolean = packageName == pkg && when (policy) {
        Policy.IMMEDIATE -> true
        Policy.AFTER_1_MIN -> android.os.SystemClock.elapsedRealtime() - authenticatedAt < 60_000L
        Policy.SCREEN_OFF -> true
    }
    @Synchronized fun firstAuthenticatedTargetArrivalMillis(pkg: String): Long? {
        if (targetArrivalReported || !isAuthenticated(pkg)) return null
        targetArrivalReported = true
        DebugDiagnostics.mark("authenticated_target_foreground_seen")
        return (android.os.SystemClock.elapsedRealtime() - authenticatedAt).coerceAtLeast(0L)
    }

    @Synchronized fun setPolicy(value: Policy) { policy = value; clear() }
    @Synchronized fun clear() {
        packageName = null
        authenticatedAt = 0L
        targetArrivalReported = false
        targetForegroundSeen = false
        DebugDiagnostics.mark("authentication_session_cleared")
    }
}
