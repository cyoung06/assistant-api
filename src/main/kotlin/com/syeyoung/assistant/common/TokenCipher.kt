package com.syeyoung.assistant.common

import com.syeyoung.assistant.config.AppProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Refresh tokens at rest (mail, ChatGPT): AES-256-GCM with a random nonce in front, keyed by the
 * deployment's secret (MAIL_TOKEN_KEY, base64 of 32 bytes).
 */
@Component
class TokenCipher(props: AppProperties) {
    private val key: SecretKeySpec? = runCatching {
        Base64.getDecoder().decode(props.sources.mail.tokenKey.trim()).takeIf { it.size == 32 }?.let { SecretKeySpec(it, "AES") }
    }.getOrNull()

    val configured get() = key != null

    fun encrypt(text: String): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        return nonce + cipher.doFinal(text.toByteArray())
    }

    fun decrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
        return String(cipher.doFinal(data, 12, data.size - 12))
    }
}
