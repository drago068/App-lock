package com.privatelock.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.aead.AeadConfig
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.privateLockStore by preferencesDataStore(name = "private_lock_data")

class UnrecoverableDataException(cause: Throwable? = null) : Exception("Encrypted app data is unavailable", cause)

/** DataStore holds only Tink AEAD ciphertext; its Tink keyset is wrapped by Android Keystore. */
class SecureStore(context: Context) {
    private val app = context.applicationContext
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(app, "private_lock_tink_keyset", "private_lock_tink")
            .withKeyTemplate(com.google.crypto.tink.aead.AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri("android-keystore://private_app_lock_master")
            .build().keysetHandle.getPrimitive(Aead::class.java)
    }

    suspend fun put(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val plaintext = value.toByteArray(Charsets.UTF_8)
            val ciphertext = try { aead.encrypt(plaintext, key.toByteArray(Charsets.UTF_8)) }
            finally { plaintext.fill(0) }
            try {
                val encoded = Base64.getEncoder().encodeToString(ciphertext)
                app.privateLockStore.edit { it[stringPreferencesKey(key)] = encoded }
            } finally { ciphertext.fill(0) }
        }
    }

    /** Encrypt all replacement secrets before one DataStore transaction. */
    suspend fun putAll(values: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val encrypted = values.mapValues { (key, value) ->
                val plaintext = value.toByteArray(Charsets.UTF_8)
                val ciphertext = try { aead.encrypt(plaintext, key.toByteArray(Charsets.UTF_8)) }
                finally { plaintext.fill(0) }
                try { Base64.getEncoder().encodeToString(ciphertext) }
                finally { ciphertext.fill(0) }
            }
            app.privateLockStore.edit { preferences ->
                encrypted.forEach { (key, value) -> preferences[stringPreferencesKey(key)] = value }
            }
        }
    }

    suspend fun get(key: String): String? {
        return withContext(Dispatchers.IO) {
            val encoded = app.privateLockStore.data.first()[stringPreferencesKey(key)] ?: return@withContext null
            val ciphertext = try { Base64.getDecoder().decode(encoded) }
            catch (failure: Exception) { throw UnrecoverableDataException(failure) }
            try {
                val plaintext = aead.decrypt(ciphertext, key.toByteArray(Charsets.UTF_8))
                try { String(plaintext, Charsets.UTF_8) }
                finally { plaintext.fill(0) }
            } catch (failure: Exception) { throw UnrecoverableDataException(failure) }
            finally { ciphertext.fill(0) }
        }
    }
}
