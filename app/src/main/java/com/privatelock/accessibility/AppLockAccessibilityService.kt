package com.privatelock.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import com.privatelock.presentation.LockActivity
import com.privatelock.security.ProtectedPackages
import com.privatelock.security.DebugDiagnostics
import com.privatelock.data.SecureStore
import com.privatelock.domain.AuthenticationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reads package/class metadata only. It never requests or inspects window content. */
class AppLockAccessibilityService : AccessibilityService() {
    private val exceptionHandler = CoroutineExceptionHandler { _, failure ->
        DebugDiagnostics.record("accessibility_service", failure)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    @Volatile private var lockedPackages: Set<String> = emptySet()
    @Volatile private var excludedPackages: Set<String> = emptySet()
    private var lastPackage: String? = null
    private var lastEventAt = 0L
    @Volatile private var fallbackTarget: String? = null
    private var fallbackView: FrameLayout? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        DebugDiagnostics.mark("accessibility_service_connected")
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.privatelock.UPDATE_LOCKED_APPS" -> scope.launch { loadLockedPackages() }
                    android.content.Intent.ACTION_SCREEN_OFF -> AuthenticationSession.clear()
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.privatelock.UPDATE_LOCKED_APPS")
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
        scope.launch {
            SecureStore(this@AppLockAccessibilityService).put("service_enabled", "true")
            restoreLegacyLauncherVisibility()
            loadLockedPackages()
        }
    }

    private var screenReceiver: android.content.BroadcastReceiver? = null

    /** Restores aliases hidden automatically by versions before the explicit user preference existed. */
    private suspend fun restoreLegacyLauncherVisibility() {
        val secureStore = SecureStore(this)
        if (secureStore.get("launcher_hidden_by_user") != null) return
        val launcherAlias = android.content.ComponentName(this, "$packageName.LauncherAlias")
        val state = packageManager.getComponentEnabledSetting(launcherAlias)
        if (state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            packageManager.setComponentEnabledSetting(
                launcherAlias,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            DebugDiagnostics.mark("legacy_launcher_alias_restored")
        }
        secureStore.put("launcher_hidden_by_user", "false")
    }

    private suspend fun loadLockedPackages() {
        val secureStore = SecureStore(this)
        excludedPackages = ProtectedPackages.deviceExclusions(this) + packageName
        val updated = secureStore.get("locked_apps").orEmpty().split(',').filter(String::isNotBlank).toSet()
        lockedPackages = updated
        val configuredPolicy = secureStore.get("relock_policy") ?: "IMMEDIATE"
        val policy = runCatching { AuthenticationSession.Policy.valueOf(configuredPolicy) }
            .getOrDefault(AuthenticationSession.Policy.IMMEDIATE)
        AuthenticationSession.setPolicy(policy)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        val now = android.os.SystemClock.elapsedRealtime()
        if (packageName == lastPackage && now - lastEventAt < 350) return
        lastPackage = packageName
        lastEventAt = now
        // The app's own lock screen is an interstitial: it must not count as leaving the protected app.
        if (packageName == this.packageName) {
            removeLaunchFallback()
            return
        }
        AuthenticationSession.onForegroundChanged(packageName)
        if (packageName in lockedPackages) {
            AuthenticationSession.firstAuthenticatedTargetArrivalMillis(packageName)?.let {
                DebugDiagnostics.timing("authenticated_target_window_after_auth", it)
            }
        }
        if (packageName !in lockedPackages || AuthenticationSession.isAuthenticated(packageName)) return
        if (packageName in excludedPackages) return
        if (fallbackTarget == packageName) return
        DebugDiagnostics.mark("protected_window_lock_requested")
        val lockIntent = android.content.Intent(this, LockActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LockActivity.EXTRA_PACKAGE, packageName)
            putExtra("window_class", className)
        }
        // Cover immediately so a silent background-start denial cannot expose the protected app.
        showLaunchFallback(packageName, lockIntent)
        try {
            val launchStarted = android.os.SystemClock.elapsedRealtime()
            startActivity(lockIntent)
            DebugDiagnostics.timing("service_start_lock_activity_call", android.os.SystemClock.elapsedRealtime() - launchStarted)
            DebugDiagnostics.mark("lock_activity_start_returned")
        } catch (failure: RuntimeException) {
            DebugDiagnostics.record("lock_activity_start", failure)
            // The overlay remains and offers a user-initiated retry.
        }
    }

    /** Covers the protected app until its credential Activity is foregrounded. */
    private fun showLaunchFallback(target: String, lockIntent: android.content.Intent) {
        android.os.Handler(mainLooper).post {
            if (fallbackView != null) return@post
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
                setBackgroundColor(Color.rgb(246, 245, 239))
            }
            content.addView(TextView(this).apply {
                text = "App locked"
                textSize = 28f
                setTextColor(Color.rgb(30, 43, 35))
                gravity = Gravity.CENTER
            })
            content.addView(TextView(this).apply {
                text = "Tap below to open the credential screen."
                textSize = 16f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(22))
            })
            val continueButton = Button(this).apply {
                text = "Opening credential screen…"
                isEnabled = false
                setOnClickListener {
                    try { startActivity(lockIntent) }
                    catch (failure: RuntimeException) {
                        DebugDiagnostics.record("lock_activity_retry", failure)
                        // Keep the covering overlay in place.
                    }
                }
            }
            content.addView(continueButton)
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(246, 245, 239))
                addView(content, FrameLayout.LayoutParams(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                windowManager.addView(overlay, params)
                fallbackView = overlay
                fallbackTarget = target
                overlay.requestFocus()
                DebugDiagnostics.mark("lock_fallback_overlay_added")
                android.os.Handler(mainLooper).postDelayed({
                    if (fallbackView === overlay) {
                        continueButton.text = "Continue to unlock"
                        continueButton.isEnabled = true
                    }
                }, 750L)
            } catch (failure: RuntimeException) {
                DebugDiagnostics.record("lock_overlay_add", failure)
                fallbackView = null
                fallbackTarget = null
            }
        }
    }

    private val windowManager: WindowManager by lazy {
        getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    }

    private fun removeLaunchFallback() {
        android.os.Handler(mainLooper).post {
            fallbackView?.let { view ->
                try { windowManager.removeView(view) }
                catch (failure: RuntimeException) { DebugDiagnostics.record("lock_overlay_remove", failure) }
            }
            fallbackView = null
            fallbackTarget = null
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() = Unit
    override fun onDestroy() {
        DebugDiagnostics.mark("accessibility_service_destroyed")
        removeLaunchFallback()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }
}
