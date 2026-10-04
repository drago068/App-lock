package com.privatelock.security

import android.os.Build
import com.privatelock.BuildConfig
import java.io.File

/** Passive environment checks only report risk signals; they never attempt to change the device. */
object EnvironmentChecker {
    fun warnings(): List<String> = buildList {
        if (BuildConfig.DEBUG) {
            add("This is a debuggable build. Do not distribute it as a release.")
        }
        val rootIndicators = listOf(
            Build.TAGS?.contains("test-keys", ignoreCase = true) == true,
            listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/adb/magisk").any { File(it).exists() }
        )
        if (rootIndicators.any { it }) add("The device may be rooted. This app cannot protect against root-level access.")
    }
}
