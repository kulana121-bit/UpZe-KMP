package com.ravi.musicspot.core

/** Cross-platform crypto utilities for JioSaavn, Innertube, and Accounts */
expect object Crypto {
    /** Decrypts JioSaavn media tokens (DES/ECB/PKCS5Padding with key "38346591") */
    fun decryptDesEcb(base64Ciphertext: String, key: String = "38346591"): String

    /** Computes SHA-1 hex string for YouTube SAPISIDHASH */
    fun sha1(input: String): String

    /** Computes MD5 hex string for Subsonic authentication */
    fun md5(input: String): String

    /** Computes SHA-256 hex string for SponsorBlock */
    fun sha256(input: String): String

    /** Computes HMAC-SHA1 for Spotify TOTP */
    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray

    /** Decompresses raw zlib stream */
    fun zlibInflate(data: ByteArray): ByteArray
}

/** Seals and opens secrets. Kept in Keystore on Android and Keychain on iOS. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String
}

expect fun createPlatformSecretBox(): SecretBox
