package com.privatelock.security

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import com.privatelock.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persisted attempt state is AEAD encrypted; elapsed time and boot count resist wall-clock changes. */
class RateLimiter(context: Context, private val store: SecureStore = SecureStore(context.applicationContext)) {
    private val app = context.applicationContext
    private val schedule = longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
    private data class State(val failures: Int, val end: Long, val boot: Int)
    private var lastReadState: State? = null

    suspend fun remainingMillis(): Long {
        val current = read()
        if (current.failures < 5) return 0L
        val remainingRound = (current.failures / 5 - 1).coerceAtLeast(0)
        val nowBoot = bootCount()
        if (current.boot != nowBoot) {
            if (current.end <= 0L) {
                write(State(current.failures, 0L, nowBoot))
                return 0L
            }
            val replacement = State(current.failures, SystemClock.elapsedRealtime() + duration(remainingRound), nowBoot)
            write(replacement)
            return duration(remainingRound)
        }
        return (current.end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    suspend fun recordFailure(): Long {
        val state = read()
        val failures = state.failures + 1
        if (failures < 5 || failures % 5 != 0) {
            write(State(failures, 0L, bootCount()))
            return 0L
        }
        val pause = duration(failures / 5 - 1)
        write(State(failures, SystemClock.elapsedRealtime() + pause, bootCount()))
        return pause
    }

    suspend fun reset() {
        val current = lastReadState ?: read()
        if (current.failures == 0 && current.end == 0L && current.boot == bootCount()) return
        write(State(0, 0L, bootCount()))
    }

    private fun duration(round: Int) = schedule[round.coerceIn(schedule.indices)]

    private suspend fun read(): State {
        val fields = store.get("attempt_state")?.split(':')
        if (fields == null) return State(0, 0L, bootCount()).also { lastReadState = it }
        if (fields.size == 3) {
            try { return State(fields[0].toInt(), fields[1].toLong(), fields[2].toInt()).also { lastReadState = it } }
            catch (failure: NumberFormatException) { DebugDiagnostics.record("rate_limit_state_parse", failure) }
        } else {
            DebugDiagnostics.record("rate_limit_state_shape", IllegalStateException("Malformed encrypted attempt state"))
        }
        // Corrupt state must not silently clear failed attempts; require the current minimum lockout.
        val locked = State(5, SystemClock.elapsedRealtime() + duration(0), bootCount())
        write(locked)
        return locked
    }

    private suspend fun write(state: State) {
        store.put("attempt_state", "${state.failures}:${state.end}:${state.boot}")
        lastReadState = state
    }
    private suspend fun bootCount(): Int = withContext(Dispatchers.IO) {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }
}
