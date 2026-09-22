package com.opensolr.mail.push

import android.content.Context
import android.util.Base64
import com.opensolr.mail.data.SecureStore
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 8291 Web Push encryption, receiving side. Each mail account's subscription has its own P-256
 * key pair and auth secret; they never leave the phone, so the relay on opensolr.com only ever
 * carries ciphertext it cannot open.
 */
object WebPush {

    data class Keys(val p256dh: String, val auth: String)

    private val params by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
    }

    private fun sp(context: Context) = context.getSharedPreferences("webpush", Context.MODE_PRIVATE)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** A new key pair and auth secret for [accountKey], replacing any earlier one. */
    fun newKeys(context: Context, accountKey: String): Keys {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        val pub = uncompressed(pair.public as ECPublicKey)
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(context).edit()
            .putString("priv_$accountKey", SecureStore.encrypt(b64(pair.private.encoded)))
            .putString("pub_$accountKey", b64(pub))
            .putString("auth_$accountKey", SecureStore.encrypt(b64(auth)))
            .commit()
        return Keys(b64(pub), b64(auth))
    }

    fun forget(context: Context, accountKey: String) {
        sp(context).edit().remove("priv_$accountKey").remove("pub_$accountKey").remove("auth_$accountKey").apply()
    }

    /** Decrypts one aes128gcm message (RFC 8188) sent to [accountKey]'s subscription; null when it is not ours or is damaged. */
    fun decrypt(context: Context, accountKey: String, body: ByteArray): String? = runCatching {
        val s = sp(context)
        val priv = SecureStore.decrypt(s.getString("priv_$accountKey", null) ?: return null)?.let { unb64(it) } ?: return null
        val uaPublic = unb64(s.getString("pub_$accountKey", null) ?: return null)
        val auth = SecureStore.decrypt(s.getString("auth_$accountKey", null) ?: return null)?.let { unb64(it) } ?: return null

        val buf = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buf.get(it) }
        buf.getInt()
        val idLen = buf.get().toInt() and 0xFF
        val asPublic = ByteArray(idLen).also { buf.get(it) }
        val cipherText = ByteArray(buf.remaining()).also { buf.get(it) }

        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(priv))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKeyOf(asPublic), true)
        val ecdh = agreement.generateSecret()

        val prkKey = hmac(auth, ecdh)
        val ikm = hmac(prkKey, "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val plain = cipher.doFinal(cipherText)
        var end = plain.size - 1
        while (end >= 0 && plain[end].toInt() == 0) end--
        if (end < 0 || plain[end].toInt() != 2) return null
        String(plain, 0, end, Charsets.UTF_8)
    }.getOrNull()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun uncompressed(k: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
        return byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
    }

    private fun publicKeyOf(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 65 && raw[0].toInt() == 4)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }
}
