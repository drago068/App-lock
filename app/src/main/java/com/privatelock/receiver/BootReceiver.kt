package com.privatelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The platform rebinds enabled accessibility services; boot only resets transient state. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().remove("authenticated_until").apply()
        }
    }
}
