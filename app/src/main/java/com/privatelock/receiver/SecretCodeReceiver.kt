package com.privatelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.privatelock.presentation.MainActivity

/** The secret dialer code only opens the normal credential gate; it is never an unlock bypass. */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}
