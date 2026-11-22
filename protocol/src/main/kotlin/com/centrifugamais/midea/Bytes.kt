package com.centrifugamais.midea

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Unsigned byte at [i]. */
internal fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0) { "hex string with odd length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal object Hash {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
    fun sha256Hex(s: String): String = sha256(s.toByteArray(Charsets.UTF_8)).toHex()
    fun md5Hex(s: String): String = md5(s.toByteArray(Charsets.UTF_8)).toHex()
    fun hmacSha256Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).toHex()
    }
}

internal object Aes {
    fun ecbEncrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    fun ecbDecrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    fun cbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray, padding: Boolean): ByteArray =
        Cipher.getInstance(if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun cbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray, padding: Boolean): ByteArray =
        Cipher.getInstance(if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
}
