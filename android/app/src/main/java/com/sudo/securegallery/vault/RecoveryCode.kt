package com.sudo.securegallery.vault

/**
 * The recovery code (§8.3): 24 random characters from Crockford's base-32 alphabet (no I, L, O
 * or U), so 120 bits. Shown once at setup; it unlocks the vault when the PIN and biometrics are
 * lost, and on a new phone after an export (#26).
 */
object RecoveryCode {
    const val LENGTH = 24
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(): CharArray {
        val bytes = VaultCrypto.randomBytes(LENGTH)
        return CharArray(LENGTH) { ALPHABET[bytes[it].toInt() and 31] }.also { bytes.fill(0) }
    }

    /** Groups of four for display: `ABCD-EFGH-...`. */
    fun format(code: CharArray): String = code.toList().chunked(4).joinToString("-") { it.joinToString("") }

    /**
     * What the user typed, made canonical: case, spaces and dashes ignored, and the look-alike
     * letters O, I and L read as 0, 1 and 1. Returns null if it cannot be a recovery code.
     */
    fun normalize(typed: CharArray): CharArray? {
        val out = StringBuilder()
        for (c in typed) {
            when (val u = c.uppercaseChar()) {
                ' ', '-' -> Unit
                'O' -> out.append('0')
                'I', 'L' -> out.append('1')
                else -> if (u in ALPHABET) out.append(u) else return null
            }
        }
        if (out.length != LENGTH) return null
        return CharArray(LENGTH) { out[it] }.also { out.setLength(0) }
    }
}
