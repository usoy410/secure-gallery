package com.sudo.securegallery.vault

import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stands in for the Keystore HMAC key in JVM tests. */
class FakeDeviceSecret(seed: Byte = 7) : DeviceSecret {
    private val key = SecretKeySpec(ByteArray(32) { seed }, "HmacSHA256")
    override fun mix(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input)
}

/** A clock the test moves by hand. */
class FakeClock(var millis: Long = 1_000_000L) : () -> Long {
    override fun invoke() = millis
}

/** Few PBKDF2 rounds keep the tests fast; production uses [Vault.PBKDF2_ITERATIONS]. */
fun testVault(
    dir: File,
    secret: DeviceSecret = FakeDeviceSecret(),
    clock: FakeClock = FakeClock(),
    afterWrite: (File) -> Unit = {},
    biometric: BiometricKey? = null,
) = Vault(VaultStore(dir), secret, biometric, now = clock, iterations = 1_000, afterWrite = afterWrite)

fun flipLastByte(file: File) {
    val bytes = file.readBytes()
    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
    file.writeBytes(bytes)
}
