package com.sudo.securegallery.vault

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM building blocks for the vault (§8.3). Plain JVM crypto, so it runs in unit tests.
 *
 * A vault file is `SGV1` + 12-byte IV + ciphertext + 16-byte tag. The magic bytes and the item
 * ID are authenticated as associated data, so a file renamed to another item fails to decrypt.
 */
internal object VaultCrypto {
    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = "SGV1".toByteArray()
    private const val CHUNK = 64 * 1024
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    /** PBKDF2-HMAC-SHA256 of a PIN or recovery code. */
    fun pbkdf2(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypts a small value (a data key) under [kek]: IV + ciphertext + tag. */
    fun seal(kek: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = cipher(Cipher.ENCRYPT_MODE, kek, iv, aad)
        return iv + cipher.doFinal(plaintext)
    }

    /** Reverses [seal]; throws [javax.crypto.AEADBadTagException] on a wrong key or tampering. */
    fun open(kek: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE, kek, sealed.copyOfRange(0, IV_BYTES), aad)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /**
     * Streams [input] into an encrypted vault file and returns the SHA-256 of the plaintext.
     * Only ciphertext touches [target].
     */
    fun encryptFile(dek: ByteArray, itemId: String, input: InputStream, target: File): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = cipher(Cipher.ENCRYPT_MODE, dek, iv, MAGIC + itemId.toByteArray())
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            out.write(MAGIC)
            out.write(iv)
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                cipher.update(buffer, 0, read)?.let(out::write)
            }
            out.write(cipher.doFinal())
        }
        return digest.digest()
    }

    /**
     * Decrypts a vault file into [sink], which must be memory or a digest, never a file.
     * Throws [javax.crypto.AEADBadTagException] if the file was changed.
     */
    fun decryptFile(dek: ByteArray, itemId: String, source: File, sink: OutputStream) {
        source.inputStream().use { input ->
            val magic = input.readNBytesCompat(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "not a vault file" }
            val iv = input.readNBytesCompat(IV_BYTES)
            val cipher = cipher(Cipher.DECRYPT_MODE, dek, iv, MAGIC + itemId.toByteArray())
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                cipher.update(buffer, 0, read)?.let(sink::write)
            }
            sink.write(cipher.doFinal())
        }
    }

    fun sha256(file: File, dek: ByteArray, itemId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        decryptFile(dek, itemId, file, DigestSink(digest))
        return digest.digest()
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = read(out, off, n - off)
            require(read >= 0) { "vault file is truncated" }
            off += read
        }
        return out
    }

    /** Feeds decrypted bytes straight into a digest so nothing is kept. */
    private class DigestSink(private val digest: MessageDigest) : OutputStream() {
        override fun write(b: Int) = digest.update(b.toByte())
        override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
    }
}
