package com.privatelock.presentation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.privatelock.data.SecureStore
import com.privatelock.data.UnrecoverableDataException
import com.privatelock.domain.AuthenticationSession
import com.privatelock.security.CredentialCrypto
import com.privatelock.security.CredentialRules
import com.privatelock.security.DebugDiagnostics
import com.privatelock.security.EnvironmentChecker
import com.privatelock.security.ProtectedPackages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : LifecycleActivity() {
    private val store by lazy { SecureStore(this) }
    private val green = Color.rgb(48, 84, 62)
    private val ink = Color.rgb(30, 43, 35)
    private val paper = Color.rgb(246, 245, 239)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        DebugDiagnostics.lifecycle("MainActivity", "onCreate", taskId, isTaskRoot)
        lifecycleScope.launch {
            try {
                if (store.get("credential") == null) showSetup() else showSettingsGate()
            } catch (failure: UnrecoverableDataException) {
                DebugDiagnostics.record("startup_store_read", failure)
                showDataUnavailable()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugDiagnostics.lifecycle("MainActivity", "onResume", taskId, isTaskRoot)
    }

    override fun onPause() {
        DebugDiagnostics.lifecycle("MainActivity", "onPause", taskId, isTaskRoot)
        super.onPause()
    }

    override fun onStop() {
        DebugDiagnostics.lifecycle("MainActivity", "onStop", taskId, isTaskRoot)
        super.onStop()
    }

    override fun onDestroy() {
        DebugDiagnostics.lifecycle("MainActivity", "onDestroy", taskId, isTaskRoot)
        super.onDestroy()
    }

    private fun showDataUnavailable() {
        val root = base("App data unavailable", "Android Keystore can no longer decrypt this app's data. Credentials and settings cannot be recovered. Clear this app's data or reinstall to set it up again.")
        root.addView(button("Close") { finish() })
    }

    private fun showSettingsGate(onUnlocked: () -> Unit = { showDashboard() }) {
        val root = base("Unlock settings", "Enter your PIN to open Connector settings.")
        val pin = EditText(this).apply {
            hint = "PIN"; inputType = 0x00000012; gravity = Gravity.CENTER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextIsSelectable(false); setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val pattern = PatternInputView(this).apply { contentDescription = "Draw pattern to unlock settings"; visibility = View.GONE }
        val status = TextView(this).apply { gravity = Gravity.CENTER; setTextColor(Color.DKGRAY) }
        var enteredPattern: String? = null
        pattern.onPatternComplete = { path ->
            enteredPattern = CredentialRules.canonicalPattern(path)
            status.text = if (enteredPattern == null) "Use a valid pattern with at least 6 dots" else "Pattern entered"
        }
        root.addView(pin, match()); root.addView(pattern, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))); root.addView(status)
        val limiter = com.privatelock.security.RateLimiter(this, store)
        lateinit var unlockButton: Button
        unlockButton = button("Unlock settings") {
            unlockButton.isEnabled = false
            lifecycleScope.launch {
                var unlocked = false
                try {
                    val remaining = limiter.remainingMillis()
                    if (remaining > 0) { status.text = "Try again in ${(remaining + 999) / 1000} seconds"; return@launch }
                    val fields = store.get("credential")?.split('|')
                    if (fields == null || fields.size !in 4..5 || fields[0] !in setOf("PIN", "PATTERN")) {
                        status.text = "Saved credential is unavailable. Clear app data to set up again."
                        return@launch
                    }
                    val supplied = if (fields[0] == "PIN") pin.text.toString() else enteredPattern
                    if (supplied == null) { status.text = "Draw your pattern first"; return@launch }
                    val verifier = CredentialCrypto.Verifier(
                        android.util.Base64.decode(fields[1], android.util.Base64.NO_WRAP),
                        android.util.Base64.decode(fields[2], android.util.Base64.NO_WRAP), fields[3],
                        fields.getOrNull(4)?.toIntOrNull() ?: CredentialCrypto.LEGACY_ITERATIONS)
                    val valid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        CredentialCrypto.verify(supplied.toCharArray(), verifier)
                    }
                    if (valid) { limiter.reset(); unlocked = true; onUnlocked() }
                    else {
                        val pause = limiter.recordFailure(); pin.text?.clear(); enteredPattern = null
                        status.text = if (pause > 0) "Too many attempts. Locked for ${pause / 1000} seconds." else "Incorrect credential"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DebugDiagnostics.record("settings_authentication", failure)
                    pin.text?.clear(); enteredPattern = null
                    status.text = "Could not verify the credential. Please try again."
                } finally {
                    if (!unlocked) unlockButton.isEnabled = true
                }
            }
        }
        unlockButton.isEnabled = false
        root.addView(unlockButton)
        lifecycleScope.launch {
            try {
                when (store.get("credential")?.substringBefore('|')) {
                    "PIN" -> { pin.visibility = View.VISIBLE; pattern.visibility = View.GONE; unlockButton.isEnabled = true }
                    "PATTERN" -> { pin.visibility = View.GONE; pattern.visibility = View.VISIBLE; unlockButton.isEnabled = true }
                    else -> status.text = "Saved credential is unavailable. Clear app data to set up again."
                }
            } catch (failure: UnrecoverableDataException) {
                DebugDiagnostics.record("settings_credential_load", failure)
                status.text = "Encrypted app data is unavailable. Clear app data to set up again."
            }
        }
    }

    private fun base(title: String, subtitle: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(38), dp(26), dp(24))
            setBackgroundColor(paper)
        }
        root.addView(TextView(this).apply { text = title; textSize = 30f; setTextColor(ink); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = subtitle; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, dp(24)) })
        setContentView(ScrollView(this).apply { addView(root) })
        return root
    }

    private fun showSetup() {
        val root = base("Your apps,\nunder your lock.", "Connector works offline. Choose a four-digit PIN or a pattern.")
        val credentialType = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val pinOption = RadioButton(this).apply { id = View.generateViewId(); text = "PIN"; isChecked = true }
        val patternOption = RadioButton(this).apply { id = View.generateViewId(); text = "Pattern" }
        credentialType.addView(pinOption); credentialType.addView(patternOption)
        root.addView(credentialType)
        val pin = EditText(this).apply {
            hint = "New PIN"; inputType = 0x00000012; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextIsSelectable(false); setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val confirm = EditText(this).apply {
            hint = "Confirm PIN"; inputType = 0x00000012; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextIsSelectable(false); setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val patternLabel = TextView(this).apply { text = "Draw a pattern with at least 6 dots"; setTextColor(ink) }
        val firstPattern = PatternInputView(this).apply { contentDescription = "Draw your new unlock pattern" }
        val confirmPattern = PatternInputView(this).apply { contentDescription = "Confirm your unlock pattern" }
        val firstPatternValue = TextView(this).apply { setTextColor(Color.DKGRAY) }
        val confirmPatternValue = TextView(this).apply { setTextColor(Color.DKGRAY) }
        firstPattern.onPatternComplete = { path ->
            val canonical = CredentialRules.canonicalPattern(path)
            firstPatternValue.text = canonical?.let { "Pattern captured" } ?: "Use at least 6 dots; avoid straight and L-shaped patterns"
            firstPattern.tag = canonical
        }
        confirmPattern.onPatternComplete = { path ->
            val canonical = CredentialRules.canonicalPattern(path)
            confirmPatternValue.text = if (canonical != null) "Confirmation captured" else "Pattern is too simple"
            confirmPattern.tag = canonical
        }
        val patternViews = listOf(patternLabel, firstPattern, firstPatternValue, confirmPattern, confirmPatternValue)
        fun selectMode(isPattern: Boolean) {
            pin.visibility = if (isPattern) View.GONE else View.VISIBLE
            confirm.visibility = if (isPattern) View.GONE else View.VISIBLE
            patternViews.forEach { it.visibility = if (isPattern) View.VISIBLE else View.GONE }
        }
        root.addView(pin, match()); root.addView(confirm, match())
        root.addView(patternLabel, match())
        root.addView(firstPattern, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        root.addView(firstPatternValue, match())
        root.addView(confirmPattern, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        root.addView(confirmPatternValue, match())
        selectMode(false)
        lateinit var createButton: Button
        createButton = button("Create PIN") {
            val isPattern = credentialType.checkedRadioButtonId == patternOption.id
            val type = if (isPattern) "PATTERN" else "PIN"
            val first = if (isPattern) firstPattern.tag as? String else pin.text.toString()
            val second = if (isPattern) confirmPattern.tag as? String else confirm.text.toString()
            if (first == null || second == null) { toast("Enter and confirm your ${type.lowercase()}"); return@button }
            if (first != second) { toast("Entries do not match"); return@button }
            if (!isPattern && !CredentialRules.validPin(first)) { toast("Use a four-digit PIN that is not simple or repeated"); return@button }
            lifecycleScope.launch {
                createButton.isEnabled = false
                try {
                    val verifier = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        CredentialCrypto.create(first.toCharArray(), "private_lock_credential")
                    }
                    store.putAll(mapOf(
                        "credential" to "$type|${b64(verifier.salt)}|${b64(verifier.value)}|${verifier.alias}|${verifier.iterations}",
                        "setup_complete" to "true"
                    ))
                    showAppSelection()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DebugDiagnostics.record("setup_credential_save", failure)
                    createButton.isEnabled = true
                    toast("Could not securely save setup. Please try again.")
                }
            }
        }
        credentialType.setOnCheckedChangeListener { _, checkedId ->
            val isPattern = checkedId == patternOption.id
            selectMode(isPattern)
            createButton.text = if (isPattern) "Create pattern" else "Create PIN"
        }
        root.addView(createButton)
    }

    private fun showAppSelection() {
        val root = base("Choose apps", "Settings starts protected. The app itself, your launcher, keyboard and system components stay excluded.")
        val pm = packageManager
        val excludedPackages = ProtectedPackages.deviceExclusions(this) + packageName
        val launchablePackages = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
        val settingsPackage = ProtectedPackages.SETTINGS.takeIf { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        }
        val candidates = (launchablePackages + listOfNotNull(settingsPackage)).distinct().filterNot {
            it in excludedPackages
        }.sortedBy { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString().lowercase() }
        val chosen = linkedSetOf(ProtectedPackages.SETTINGS)
        val filter = EditText(this).apply { hint = "Search apps"; setSingleLine(true); isEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render(query: String = "") {
            list.removeAllViews()
            val filtered = candidates.filter { pkg ->
                val name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                name.contains(query, true)
            }
            filtered.forEach { pkg ->
                val appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                val toggle = CheckBox(this).apply {
                    text = if (pkg == ProtectedPackages.SETTINGS) "$appName  ·  protects service settings" else appName
                    isChecked = pkg in chosen; setTextColor(ink)
                    setOnCheckedChangeListener { _, checked -> if (checked) chosen += pkg else chosen -= pkg }
                }
                list.addView(toggle)
            }
        }
        filter.addTextChangedListener(SimpleWatcher { render(it) })
        val loadStatus = TextView(this).apply { setTextColor(Color.DKGRAY) }
        root.addView(filter, match()); root.addView(loadStatus); root.addView(list); render()
        lateinit var saveButton: Button
        saveButton = button("Save and enable protection") {
            lifecycleScope.launch {
                saveButton.isEnabled = false
                try {
                    val saveStarted = android.os.SystemClock.elapsedRealtime()
                    store.put("locked_apps", chosen.joinToString(","))
                    DebugDiagnostics.timing("save_selected_apps_store", android.os.SystemClock.elapsedRealtime() - saveStarted)
                    sendBroadcast(Intent("com.privatelock.UPDATE_LOCKED_APPS").setPackage(packageName))
                    DebugDiagnostics.mark("save_selected_apps_broadcast_sent")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DebugDiagnostics.record("protected_apps_save", failure)
                    loadStatus.text = "Could not save the protected apps. Please try again."
                    saveButton.isEnabled = true
                }
            }
        }
        saveButton.isEnabled = false
        root.addView(saveButton)
        lifecycleScope.launch {
            try {
                store.get("locked_apps").orEmpty().split(',').filter { it in candidates }
                    .forEach { chosen += it }
                render(filter.text.toString())
                filter.isEnabled = true
                saveButton.isEnabled = true
                loadStatus.text = ""
            } catch (failure: UnrecoverableDataException) {
                DebugDiagnostics.record("protected_apps_load", failure)
                loadStatus.text = "Protected apps could not be read. Clear app data to set up again."
            }
        }
    }

    private fun showDashboard() {
        val root = base("Protection", "Your app list is encrypted and stored on this device.")
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/.accessibility.AppLockAccessibilityService", ignoreCase = true) == true
        if (!enabled) {
            val status = TextView(this).apply {
                text = "Protection is paused. Enable the accessibility service to lock selected apps."
                setTextColor(Color.rgb(150, 60, 40)); textSize = 16f
            }
            root.addView(status)
            lifecycleScope.launch {
                if (store.get("service_enabled") == "true") {
                    status.text = "Warning: the Accessibility service was disabled. Protection is paused until you enable it again."
                }
                store.put("service_enabled", "false")
            }
            root.addView(button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        } else {
            root.addView(TextView(this).apply { text = "Accessibility service is enabled."; setTextColor(green); textSize = 16f })
            lifecycleScope.launch { store.put("service_enabled", "true") }
        }
        EnvironmentChecker.warnings().forEach { warning ->
            root.addView(TextView(this).apply {
                text = warning; setTextColor(Color.rgb(150, 60, 40)); textSize = 14f; setPadding(0, dp(10), 0, 0)
            })
        }
        root.addView(button("Manage protected apps") { showAppSelection() })
        root.addView(button("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val policyTitle = TextView(this).apply {
            text = "Re-lock policy"; textSize = 17f; setTextColor(ink); setPadding(0, dp(22), 0, dp(8))
        }
        val policies = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val immediate = RadioButton(this).apply { id = View.generateViewId(); text = "Immediately when leaving the app" }
        val oneMinute = RadioButton(this).apply { id = View.generateViewId(); text = "After 1 minute" }
        val screenOff = RadioButton(this).apply { id = View.generateViewId(); text = "When the screen turns off" }
        policies.addView(immediate); policies.addView(oneMinute); policies.addView(screenOff)
        policies.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                oneMinute.id -> "AFTER_1_MIN"
                screenOff.id -> "SCREEN_OFF"
                else -> "IMMEDIATE"
            }
            lifecycleScope.launch {
                store.put("relock_policy", selected)
                AuthenticationSession.setPolicy(AuthenticationSession.Policy.valueOf(selected))
            }
        }
        root.addView(policyTitle); root.addView(policies)
        lifecycleScope.launch {
            val selected = store.get("relock_policy") ?: "IMMEDIATE"
            AuthenticationSession.setPolicy(runCatching { AuthenticationSession.Policy.valueOf(selected) }.getOrDefault(AuthenticationSession.Policy.IMMEDIATE))
            policies.check(when (selected) {
                "AFTER_1_MIN" -> oneMinute.id
                "SCREEN_OFF" -> screenOff.id
                else -> immediate.id
            })
        }
        root.addView(TextView(this).apply { text = "Connector is a privacy barrier against casual access. It cannot stop uninstalling, root or ADB access, or system-level changes."; setTextColor(Color.DKGRAY); textSize = 13f; setPadding(0, dp(28), 0, 0) })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(green))
        setOnClickListener { action() }
    }
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

private class SimpleWatcher(val onChange: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
