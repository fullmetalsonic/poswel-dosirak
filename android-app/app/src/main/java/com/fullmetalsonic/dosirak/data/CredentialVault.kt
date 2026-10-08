package com.fullmetalsonic.dosirak.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fullmetalsonic.dosirak.site.Credentials
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("encrypted_account", Context.MODE_PRIVATE)
    private val alias = "dosirak.account.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
    fun hasCredentials(): Boolean = prefs.contains("cipher")
    @Synchronized fun save(userId: String, password: String) {
        require(userId.isNotBlank() && password.isNotEmpty())
        val json = JsonObject().apply { addProperty("uid", userId.trim()); addProperty("pwd", password) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun load(): Credentials? {
        val value = prefs.getString("cipher", null) ?: return null
        val iv = prefs.getString("iv", null) ?: error("저장 계정을 다시 입력하세요.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        val json = JsonParser.parseString(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)).asJsonObject
        return Credentials(json.get("uid").asString, json.get("pwd").asString)
    }
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) }
}
