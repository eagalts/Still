package com.eagalts.still

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование своих файлов Still (подход Plume, `Vault.kt`, упрощённо): AES-256-GCM, ключ живёт в аппаратном
 * хранилище Android и из него не выходит. Всё, что Still пишет на диск, — пересчитываемый индекс:
 * потеря ключа = пересчёт, а не потеря фото, поэтому второго ключа и карантина, как в Plume, здесь не нужно.
 */
object Vault {
    private const val ALIAS = "still_index"
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val IV = 12

    @Volatile private var softKey: SecretKey? = null

    private fun key(): SecretKey {
        if (System.getProperty("still.vaultSoftware") == "1") {
            return softKey ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { softKey = it }
        }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return MAGIC + c.iv + c.doFinal(data)
    }

    /** null — файл чужой, битый или ключ потерян: индекс просто считается заново. */
    fun open(raw: ByteArray): ByteArray? = runCatching {
        if (raw.size < MAGIC.size + IV || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, MAGIC.size, IV))
        c.doFinal(raw, MAGIC.size + IV, raw.size - MAGIC.size - IV)
    }.getOrNull()

    /** Запись целиком через временный файл: оборванная запись не оставит полфайла. */
    fun write(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(seal(data))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun read(f: File): ByteArray? = if (f.isFile) runCatching { f.readBytes() }.getOrNull()?.let(::open) else null
}
