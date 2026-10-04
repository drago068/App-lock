package com.privatelock.security

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager

/** Central exclusion policy: these packages are never presented as lockable. */
object ProtectedPackages {
    const val SETTINGS = "com.android.settings"
    const val SYSTEM_UI = "com.android.systemui"
    const val PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"
    const val PACKAGE_INSTALLER = "com.google.android.packageinstaller"
    val fixed = setOf(SYSTEM_UI, PERMISSION_CONTROLLER, PACKAGE_INSTALLER,
        "com.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller")

    /** Resolve device-specific home, keyboard, and dialer packages once during setup/service startup. */
    fun deviceExclusions(context: Context): Set<String> {
        val pm = context.packageManager
        val excluded = fixed.toMutableSet()
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).resolveActivity(pm)
            ?.packageName?.let(excluded::add)
        val inputMethods = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList
        inputMethods.mapTo(excluded) { it.packageName }
        val dialerActions = listOf(Intent.ACTION_DIAL, "android.intent.action.EMERGENCY_DIAL")
        for (action in dialerActions) {
            pm.queryIntentActivities(Intent(action), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(excluded) { it.activityInfo.packageName }
        }
        return excluded
    }

    fun isExcluded(packageName: String, ownPackage: String, launcherPackage: String?, inputMethods: Set<String>, dialerPackages: Set<String>): Boolean =
        packageName == ownPackage || packageName == launcherPackage || packageName in fixed ||
            packageName in inputMethods || packageName in dialerPackages
}
