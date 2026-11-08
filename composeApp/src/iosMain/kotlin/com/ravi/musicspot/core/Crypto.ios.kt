package com.ravi.musicspot.core

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.Foundation.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
actual object Crypto {
    actual fun decryptDesEcb(base64Ciphertext: String, key: String): String {
        val data = NSData.create(base64EncodedString = base64Ciphertext.trim(), options = 0u) ?: return ""
        val keyData = (key as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return ""
        val buffer = ByteArray(data.length.toInt() + kCCBlockSizeDES.toInt())
        val moved = ULongArray(1)

        buffer.usePinned { pinned ->
            moved.usePinned { movedPin ->
                CCCrypt(
                    kCCDecrypt,
                    kCCAlgorithmDES,
                    kCCOptionPKCS7Padding or kCCOptionECBMode,
                    keyData.bytes,
                    kCCKeySizeDES.toULong(),
                    null,
                    data.bytes,
                    data.length,
                    pinned.addressOf(0),
                    buffer.size.toULong(),
                    movedPin.addressOf(0)
                )
            }
        }
        val outLen = moved[0].toInt().coerceIn(0, buffer.size)
        if (outLen == 0) return ""
        return runCatching { buffer.copyOf(outLen).decodeToString() }.getOrDefault("")
    }

    actual fun sha1(input: String): String {
        val data = (input as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return ""
        val digest = ByteArray(CC_SHA1_DIGEST_LENGTH)
        digest.usePinned { pinned ->
            CC_SHA1(data.bytes, data.length.toUInt(), pinned.addressOf(0).reinterpret())
        }
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    actual fun md5(input: String): String {
        val data = (input as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return ""
        val digest = ByteArray(CC_MD5_DIGEST_LENGTH)
        digest.usePinned { pinned ->
            CC_MD5(data.bytes, data.length.toUInt(), pinned.addressOf(0).reinterpret())
        }
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    actual fun sha256(input: String): String {
        val data = (input as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return ""
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { pinned ->
            CC_SHA256(data.bytes, data.length.toUInt(), pinned.addressOf(0).reinterpret())
        }
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    actual fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val result = ByteArray(CC_SHA1_DIGEST_LENGTH)
        key.usePinned { kPinned ->
            data.usePinned { dPinned ->
                result.usePinned { rPinned ->
                    CCHmac(kCCHmacAlgSHA1, kPinned.addressOf(0), key.size.toULong(), dPinned.addressOf(0), data.size.toULong(), rPinned.addressOf(0))
                }
            }
        }
        return result
    }

    actual fun zlibInflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        memScoped {
            val strm = alloc<z_stream>()
            strm.zalloc = null
            strm.zfree = null
            strm.opaque = null
            strm.avail_in = data.size.toUInt()

            data.usePinned { pinnedIn ->
                strm.next_in = pinnedIn.addressOf(0).reinterpret()
                if (inflateInit_(strm.ptr, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
                    return ByteArray(0)
                }
                try {
                    val chunkSize = 16384
                    var outBuf = ByteArray(chunkSize)
                    var totalOut = 0
                    val outChunk = ByteArray(chunkSize)
                    outChunk.usePinned { pinnedOut ->
                        while (true) {
                            strm.avail_out = chunkSize.toUInt()
                            strm.next_out = pinnedOut.addressOf(0).reinterpret()
                            val ret = inflate(strm.ptr, Z_NO_FLUSH)
                            if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                                break
                            }
                            val produced = chunkSize - strm.avail_out.toInt()
                            if (produced > 0) {
                                if (totalOut + produced > outBuf.size) {
                                    outBuf = outBuf.copyOf((totalOut + produced) * 2)
                                }
                                for (i in 0 until produced) {
                                    outBuf[totalOut + i] = outChunk[i]
                                }
                                totalOut += produced
                            }
                            if (ret == Z_STREAM_END) break
                            if (strm.avail_in == 0u && produced == 0) break
                        }
                    }
                    return outBuf.copyOf(totalOut)
                } finally {
                    inflateEnd(strm.ptr)
                }
            }
        }
    }
}

class IosKeychainBox : SecretBox {
    override fun seal(plain: String): String {
        val data = (plain as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        return data?.base64EncodedStringWithOptions(0u) ?: plain
    }

    override fun open(sealed: String): String {
        val data = NSData.create(base64EncodedString = sealed, options = 0u) ?: return sealed
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String? ?: sealed
    }
}

actual fun createPlatformSecretBox(): SecretBox = IosKeychainBox()
