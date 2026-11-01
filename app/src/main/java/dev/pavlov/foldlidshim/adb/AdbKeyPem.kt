package dev.pavlov.foldlidshim.adb

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object AdbKeyPem {
    fun parse(text: String): ByteArray = try {
        val match = Regex("""\A\s*-----BEGIN PRIVATE KEY-----\s+([A-Za-z0-9+/=\s]+)-----END PRIVATE KEY-----\s*\z""").matchEntire(text)
            ?: error("format")
        val der = Base64.getDecoder().decode(match.groupValues[1].replace(Regex("""\s"""), ""))
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
        require(key.modulus.bitLength() == 2048 && key.publicExponent.toInt() == 65537)
        key.encoded
    } catch (_: Exception) {
        throw IllegalArgumentException("Это не ключ adb")
    }
}
