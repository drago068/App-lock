package com.privatelock.presentation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.privatelock.data.SecureStore
import com.privatelock.domain.AuthenticationSession
import com.privatelock.security.CredentialCrypto
import com.privatelock.security.CredentialRules
import com.privatelock.security.DebugDiagnostics
import com.privatelock.security.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LockActivity : LifecycleActivity() {
    companion object { const val EXTRA_PACKAGE = "locked_package" }
    private var handingOffToAnotherActivity = false
    private val store by lazy { SecureStore(this) }
    private val limiter by lazy { RateLimiter(this, store) }
    private var credentialType: String? = null
    private var credentialVerifier: CredentialCrypto.Verifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugDiagnostics.lifecycle("LockActivity", "onCreate", taskId, isTaskRoot)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(246, 245, 239))
        }
        root.addView(TextView(this).apply {
            text = "App locked"; textSize = 30f; setTextColor(Color.rgb(30, 43, 35)); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = targetPackage; textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        })

        val pin = EditText(this).apply {
            hint = "Enter PIN"; inputType = 0x00000012; gravity = Gravity.CENTER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextIsSelectable(false); setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val pattern = PatternInputView(this).apply { contentDescription = "Draw unlock pattern"; visibility = View.GONE }
        val feedback = TextView(this).apply { gravity = Gravity.CENTER; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 8) }
        var enteredPattern: String? = null
        pattern.onPatternComplete = { path ->
            enteredPattern = CredentialRules.canonicalPattern(path)
            feedback.text = if (enteredPattern == null) "Use at least 6 dots; avoid straight and L-shaped patterns" else "Pattern entered"
        }
        root.addView(pin)
        root.addView(pattern, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)))
        root.addView(feedback)
        val unlockButton = Button(this).apply { text = "Unlock"; isEnabled = false }
        unlockButton.setOnClickListener {
            unlockButton.isEnabled = false
            val unlockStartedAt = android.os.SystemClock.elapsedRealtime()
            DebugDiagnostics.checkpoint("unlock_button_pressed", unlockStartedAt)
            DebugDiagnostics.mark("unlock_pressed")
            lifecycleScope.launch {
                try {
                    val rateReadStarted = android.os.SystemClock.elapsedRealtime()
                    DebugDiagnostics.checkpoint("unlock_rate_limit_read_start", unlockStartedAt)
                    val remaining = limiter.remainingMillis()
                    DebugDiagnostics.timing("unlock_rate_limit_read", android.os.SystemClock.elapsedRealtime() - rateReadStarted)
                    DebugDiagnostics.checkpoint("unlock_rate_limit_read_end", unlockStartedAt)
                    if (remaining > 0) {
                        feedback.text = "Try again in ${(remaining + 999) / 1000} seconds"
                        pin.text?.clear(); enteredPattern = null; unlockButton.isEnabled = true
                        return@launch
                    }
                    val type = credentialType
                    val verifier = credentialVerifier
                    if (type !in setOf("PIN", "PATTERN") || verifier == null) {
                        feedback.text = "Saved credential is unavailable. Clear app data to set up again."
                        pin.text?.clear(); enteredPattern = null; unlockButton.isEnabled = true
                        return@launch
                    }
                    val supplied = if (type == "PIN") pin.text.toString() else enteredPattern
                    if (supplied == null) {
                        feedback.text = "Draw your pattern first"
                        unlockButton.isEnabled = true
                        return@launch
                    }
                    val verificationStarted = android.os.SystemClock.elapsedRealtime()
                    DebugDiagnostics.checkpoint("unlock_credential_verification_start", unlockStartedAt)
                    val valid = withContext(Dispatchers.Default) {
                        CredentialCrypto.verify(supplied.toCharArray(), verifier, "unlock")
                    }
                    DebugDiagnostics.timing("unlock_pbkdf2_hmac", android.os.SystemClock.elapsedRealtime() - verificationStarted)
                    DebugDiagnostics.checkpoint("unlock_credential_verification_complete", unlockStartedAt)
                    if (valid) {
                        val launchLookupStarted = android.os.SystemClock.elapsedRealtime()
                        val targetIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        DebugDiagnostics.timing("unlock_target_intent_lookup", android.os.SystemClock.elapsedRealtime() - launchLookupStarted)
                        if (targetIntent == null) {
                            feedback.text = "This app has no launcher screen to return to."
                            unlockButton.isEnabled = true
                            return@launch
                        }
                        val resetStarted = android.os.SystemClock.elapsedRealtime()
                        limiter.reset()
                        DebugDiagnostics.timing("unlock_rate_limit_reset", android.os.SystemClock.elapsedRealtime() - resetStarted)
                        DebugDiagnostics.checkpoint("unlock_authentication_session_update_start", unlockStartedAt)
                        AuthenticationSession.authenticate(targetPackage)
                        DebugDiagnostics.checkpoint("unlock_authentication_session_update_complete", unlockStartedAt)
                        handingOffToAnotherActivity = true
                        try {
                            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            val targetStartStarted = android.os.SystemClock.elapsedRealtime()
                            DebugDiagnostics.checkpoint("unlock_target_application_launch_start", unlockStartedAt)
                            startActivity(targetIntent)
                            DebugDiagnostics.timing("unlock_target_start_activity_call", android.os.SystemClock.elapsedRealtime() - targetStartStarted)
                            DebugDiagnostics.checkpoint("unlock_target_application_launch_call_returned", unlockStartedAt)
                            DebugDiagnostics.mark("unlock_target_start_requested")
                            enteredPattern = null
                            finish()
                            DebugDiagnostics.checkpoint("unlock_lock_activity_finish_called", unlockStartedAt)
                        } catch (failure: Exception) {
                            handingOffToAnotherActivity = false
                            AuthenticationSession.clear()
                            throw failure
                        }
                    } else {
                        val pause = limiter.recordFailure()
                        pin.text?.clear(); enteredPattern = null
                        feedback.text = if (pause > 0) "Too many attempts. Locked for ${pause / 1000} seconds." else "Incorrect credential"
                        unlockButton.isEnabled = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DebugDiagnostics.record("lock_authentication", failure)
                    pin.text?.clear(); enteredPattern = null
                    feedback.text = "Could not verify or reopen the app. Please try again."
                    unlockButton.isEnabled = true
                }
            }
        }
        root.addView(unlockButton)
        setContentView(root)

        lifecycleScope.launch {
            try {
                val credentialLoadStarted = android.os.SystemClock.elapsedRealtime()
                val fields = store.get("credential")?.split('|')
                if (fields != null && fields.size in 4..5 && fields[0] in setOf("PIN", "PATTERN")) {
                    credentialType = fields[0]
                    credentialVerifier = CredentialCrypto.Verifier(
                        android.util.Base64.decode(fields[1], android.util.Base64.NO_WRAP),
                        android.util.Base64.decode(fields[2], android.util.Base64.NO_WRAP),
                        fields[3], fields.getOrNull(4)?.toIntOrNull() ?: CredentialCrypto.LEGACY_ITERATIONS
                    )
                }
                when (credentialType) {
                    "PIN" -> { pin.visibility = View.VISIBLE; pattern.visibility = View.GONE }
                    "PATTERN" -> { pin.visibility = View.GONE; pattern.visibility = View.VISIBLE }
                    else -> feedback.text = "Saved credential is unavailable. Clear app data to set up again."
                }
                unlockButton.isEnabled = credentialVerifier != null
                DebugDiagnostics.timing("lock_credential_load", android.os.SystemClock.elapsedRealtime() - credentialLoadStarted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DebugDiagnostics.record("lock_credential_load", failure)
                feedback.text = "Encrypted app data is unavailable. Clear app data to set up again."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugDiagnostics.lifecycle("LockActivity", "onResume", taskId, isTaskRoot)
    }

    override fun onPause() {
        DebugDiagnostics.lifecycle("LockActivity", "onPause", taskId, isTaskRoot)
        super.onPause()
    }

    override fun onStop() {
        DebugDiagnostics.lifecycle("LockActivity", "onStop", taskId, isTaskRoot)
        super.onStop()
    }

    override fun onDestroy() {
        DebugDiagnostics.lifecycle("LockActivity", "onDestroy", taskId, isTaskRoot)
        super.onDestroy()
    }

    override fun onBackPressed() = goHome()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing && !handingOffToAnotherActivity) goHome()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
