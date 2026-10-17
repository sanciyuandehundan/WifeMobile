package io.wife.mobile.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the phone keeps: the link key, the computer's addresses, her offline card and talk. Encrypted with a key
 *  that never leaves Android's Keystore; nothing in the app shows the link key. */
class Vault(private val file: File) : Keep {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    @Synchronized
    override fun load(): JSONObject {
        if (!file.isFile) return JSONObject()
        val data = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return JSONObject(String(cipher.doFinal(data, 12, data.size - 12)))
    }

    @Synchronized
    fun save(content: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(content.toString().toByteArray())
        val temp = File(file.path + ".new")
        temp.writeBytes(sealed)
        temp.renameTo(file)
    }

    @Synchronized
    override fun update(change: (JSONObject) -> Unit) = save(load().also(change))

    companion object {
        private const val ALIAS = "wife-link"
    }
}
