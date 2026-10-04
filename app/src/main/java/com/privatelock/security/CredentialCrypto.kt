package com.privatelock.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.concurrent.ConcurrentHashMap

/** Credential handling deliberately persists only salted, Keystore-keyed verifiers. */
object CredentialCrypto {
    const val LEGACY_ITERATIONS = 310_000
    private const val ITERATIONS = 75_000
    private const val OUTPUT_BITS = 256
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    /** The key remains Android Keystore-backed; this only avoids repeated provider lookups per process. */
    private val keys = ConcurrentHashMap<String, SecretKey>()

    data class Verifier(
        val salt: ByteArray,
        val value: ByteArray,
        val alias: String,
        val iterations: Int = LEGACY_ITERATIONS
    )

    fun create(input: CharArray, alias: String): Verifier {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val derived = derive(input, salt, ITERATIONS)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key(alias))
            Verifier(salt, mac.doFinal(derived), alias, ITERATIONS)
        } finally {
            derived.fill(0)
            input.fill('\u0000')
        }
    }

    fun verify(input: CharArray, verifier: Verifier, timingPrefix: String? = null): Boolean {
        val deriveStarted = android.os.SystemClock.elapsedRealtime()
        if (timingPrefix != null) DebugDiagnostics.mark("${timingPrefix}_pbkdf2_start")
        val derived = try {
            derive(input, verifier.salt, verifier.iterations)
        } catch (failure: Throwable) {
            if (timingPrefix != null) {
                DebugDiagnostics.timing("${timingPrefix}_pbkdf2_duration", android.os.SystemClock.elapsedRealtime() - deriveStarted)
                DebugDiagnostics.mark("${timingPrefix}_pbkdf2_end")
            }
            throw failure
        }
        return try {
            if (timingPrefix != null) {
                DebugDiagnostics.timing("${timingPrefix}_pbkdf2_duration", android.os.SystemClock.elapsedRealtime() - deriveStarted)
                DebugDiagnostics.mark("${timingPrefix}_pbkdf2_end")
                DebugDiagnostics.mark("${timingPrefix}_hmac_start")
            }
            val hmacStarted = android.os.SystemClock.elapsedRealtime()
            try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(key(verifier.alias))
                constantTimeEquals(mac.doFinal(derived), verifier.value)
            } finally {
                if (timingPrefix != null) {
                    DebugDiagnostics.timing("${timingPrefix}_hmac_duration", android.os.SystemClock.elapsedRealtime() - hmacStarted)
                    DebugDiagnostics.mark("${timingPrefix}_hmac_end")
                }
            }
        } finally {
            derived.fill(0)
            input.fill('\u0000')
        }
    }

    private fun derive(input: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(input, salt, iterations, OUTPUT_BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun key(alias: String): SecretKey {
        keys[alias]?.let { return it }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { key ->
            keys.putIfAbsent(alias, key)
            return keys.getValue(alias)
        }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        val generated = generator.generateKey()
        keys.putIfAbsent(alias, generated)
        return keys.getValue(alias)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        var difference = a.size xor b.size
        for (i in 0 until maxOf(a.size, b.size)) difference = difference or
            ((a.getOrElse(i) { 0 }.toInt() and 0xff) xor (b.getOrElse(i) { 0 }.toInt() and 0xff))
        return difference == 0
    }
}

object CredentialRules {
    fun validPin(pin: String): Boolean {
        if (pin.length != 4 || pin.any { it !in '0'..'9' }) return false
        if (pin.all { it == pin.first() }) return false
        if ((1..(pin.length / 2)).any { blockSize ->
                pin.length % blockSize == 0 && pin.chunked(blockSize).distinct().size == 1
            }) return false
        if (pin.toList().zipWithNext().all { (a, b) -> b.code == a.code + 1 } ||
            pin.toList().zipWithNext().all { (a, b) -> b.code == a.code - 1 }) return false
        return true
    }

    /** Insert every intervening grid dot, matching Android's pattern gesture rules. */
    fun canonicalPattern(path: List<Int>): String? {
        if (path.isEmpty() || path.any { it !in 1..9 } || path.distinct().size != path.size) return null
        val result = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        for (dot in path) {
            if (result.isNotEmpty()) {
                val previous = result.last()
                val dr = (dot - 1) / 3 - (previous - 1) / 3
                val dc = (dot - 1) % 3 - (previous - 1) % 3
                val divisor = gcd(kotlin.math.abs(dr), kotlin.math.abs(dc))
                if (divisor > 1) for (step in 1 until divisor) {
                    val intermediate = previous + (dr / divisor * step) * 3 + dc / divisor * step
                    if (seen.add(intermediate)) result += intermediate
                }
            }
            result += dot
            seen += dot
        }
        if (result.size < 6) return null
        val canonical = result.joinToString("")
        return canonical.takeIf { !isStraight(canonical) && !isLShape(canonical) }
    }

    private fun isStraight(value: String): Boolean {
        val points = value.map { (it.digitToInt() - 1).let { n -> n / 3 to n % 3 } }
        return points.all { (r, c) -> (r - points[0].first) * (points.last().second - points[0].second) ==
            (c - points[0].second) * (points.last().first - points[0].first) }
    }

    private fun isLShape(value: String): Boolean {
        val points = value.map { (it.digitToInt() - 1).let { n -> n / 3 to n % 3 } }
        val directions = mutableListOf<Char>()
        for ((from, to) in points.zipWithNext()) {
            val rowDelta = to.first - from.first
            val columnDelta = to.second - from.second
            val direction = when {
                rowDelta == 0 && columnDelta != 0 -> 'H'
                columnDelta == 0 && rowDelta != 0 -> 'V'
                else -> return false
            }
            if (directions.lastOrNull() != direction) directions += direction
        }
        return directions.size == 2 && directions[0] != directions[1]
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
