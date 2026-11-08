package com.ravi.musicspot.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object Crypto {
    actual fun decryptDesEcb(base64Ciphertext: String, key: String): String {
        val c = Cipher.getInstance("DES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"))
        val decoded = Base64.getDecoder().decode(base64Ciphertext.trim())
        return String(c.doFinal(decoded), Charsets.UTF_8).trim()
    }

    actual fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    actual fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    actual fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    actual fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    actual fun zlibInflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(data)
            val bos = java.io.ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(2048)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                bos.write(buf, 0, count)
            }
            return bos.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

class AndroidGcmBox(private val keyProvider: () -> SecretKey) : SecretBox {
    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyProvider())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(out)
    }

    override fun open(sealed: String): String {
        val raw = Base64.getDecoder().decode(sealed)
        require(raw.size > 12 + 16) { "sealed value too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "musicspot.accounts.v1"

        fun keystore(): AndroidGcmBox {
            var cached: SecretKey? = null
            return AndroidGcmBox {
                cached ?: synchronized(this) {
                    cached ?: (runCatching { loadOrCreate() }.getOrElse {
                        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
                        loadOrCreate()
                    }).also { cached = it }
                }
            }
        }

        private fun loadOrCreate(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}

actual fun createPlatformSecretBox(): SecretBox = AndroidGcmBox.keystore()
