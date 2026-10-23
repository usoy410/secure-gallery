package com.sudo.securegallery.vault

import java.io.File
import java.util.Base64
import java.util.Properties

/**
 * One wrapped copy of the data key: [sealed] is the DEK encrypted under a key derived from
 * [salt] and [iterations]. Only ciphertext and parameters are stored, never the DEK or a PIN.
 */
data class WrappedKey(val salt: ByteArray, val iterations: Int, val sealed: ByteArray) {
    override fun equals(other: Any?) = other is WrappedKey && salt.contentEquals(other.salt) &&
        iterations == other.iterations && sealed.contentEquals(other.sealed)

    override fun hashCode() = 31 * (31 * salt.contentHashCode() + iterations) + sealed.contentHashCode()
}

/**
 * The vault's files in app-private storage: `keys.properties` (wrapped DEKs), `attempts.properties`
 * (wrong-PIN count), and one `<item>.sgv` per encrypted original.
 */
class VaultStore(val dir: File) {
    private val keysFile = File(dir, "keys.properties")
    private val attemptsFile = File(dir, "attempts.properties")

    fun hasKeys(): Boolean = keysFile.exists()

    fun readKey(method: String): WrappedKey? {
        val props = load(keysFile)
        val salt = props.getProperty("$method.salt") ?: return null
        return WrappedKey(
            salt = decode(salt),
            iterations = props.getProperty("$method.iterations").toInt(),
            sealed = decode(props.getProperty("$method.sealed")),
        )
    }

    fun writeKey(method: String, key: WrappedKey) {
        val props = load(keysFile)
        props.setProperty("$method.salt", encode(key.salt))
        props.setProperty("$method.iterations", key.iterations.toString())
        props.setProperty("$method.sealed", encode(key.sealed))
        save(keysFile, props)
    }

    fun removeKey(method: String) {
        val props = load(keysFile)
        listOf("salt", "iterations", "sealed").forEach { props.remove("$method.$it") }
        save(keysFile, props)
    }

    fun readAttempts(): Pair<Int, Long> {
        val props = load(attemptsFile)
        return (props.getProperty("failures")?.toInt() ?: 0) to (props.getProperty("last")?.toLong() ?: 0L)
    }

    fun writeAttempts(failures: Int, lastFailure: Long) {
        save(attemptsFile, Properties().apply {
            setProperty("failures", failures.toString())
            setProperty("last", lastFailure.toString())
        })
    }

    fun itemFile(itemId: String): File {
        require(isItemId(itemId)) { "bad item id" }
        return File(dir, "$itemId.sgv")
    }

    /** IDs of every stored original, sorted. */
    fun itemIds(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".sgv") }.orEmpty().map { it.name.removeSuffix(".sgv") }.sorted()

    companion object {
        fun isItemId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,64}"))
    }

    private fun load(file: File) = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    /** Write to a temporary file, then rename, so a crash never leaves a half-written key file. */
    private fun save(file: File, props: Properties) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.outputStream().use { props.store(it, null) }
        check(tmp.renameTo(file)) { "could not save ${file.name}" }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)
}
