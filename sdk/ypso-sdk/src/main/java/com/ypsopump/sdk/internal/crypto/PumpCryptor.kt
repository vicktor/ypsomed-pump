package com.ypsopump.sdk.internal.crypto

import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Gestiona el estado criptográfico de la sesión con la bomba: clave compartida,
 * contadores de trama y operaciones de cifrado/descifrado.
 *
 * ## Formato en cable
 * Cada mensaje cifrado tiene la estructura: `ciphertext+tag(16B) || nonce(24B)`
 * El nonce de 24 bytes va **al final** del mensaje cifrado.
 *
 * ## Contadores de trama
 * Antes de cifrar, se añaden 12 bytes al plaintext:
 * - `reboot_counter` (4B LE): número de reinicios de la bomba.
 * - `write_counter` (8B LE): contador incremental de escrituras del dispositivo.
 *
 * Al descifrar una respuesta de la bomba, se sincronizan los contadores:
 * - Si el `reboot_counter` de la bomba difiere del local, se actualiza y se
 *   reinicia el `write_counter` a 0 (la bomba se ha reiniciado).
 * - El `read_counter` se actualiza con el contador numérico de la respuesta.
 *
 * ## Sincronización obligatoria
 * Es **imprescindible** descifrar al menos una respuesta de la bomba (p. ej. llamando
 * a `getSystemStatus()`) antes de enviar cualquier comando cifrado. Sin esto,
 * el `reboot_counter` local puede ser incorrecto y la bomba rechazará el mensaje
 * con error 138 o 139.
 *
 * Los contadores y la clave se persisten en [SharedPreferences] para sobrevivir
 * al reinicio de la app.
 *
 * @property sharedKey Clave compartida de 32 bytes derivada durante el intercambio de claves.
 * @property readCounter Contador de lecturas sincronizado desde las respuestas de la bomba.
 * @property writeCounter Contador de escrituras incrementado en cada [encrypt].
 * @property rebootCounter Contador de reinicios de la bomba, sincronizado en cada [decrypt].
 */
internal class PumpCryptor(
    val sharedKey: ByteArray,
    private val prefs: SharedPreferences
) {
    /** Contador de lectura sincronizado desde la última respuesta descifrada de la bomba. */
    var readCounter: Long = prefs.getLong("read_counter", 0)
        private set
    /** Contador de escritura que se incrementa en cada llamada a [encrypt]. */
    var writeCounter: Long = prefs.getLong("write_counter", 0)
        private set
    /** Contador de reinicios de la bomba; se sincroniza en cada [decrypt]. */
    var rebootCounter: Int = prefs.getInt("reboot_counter", 0)
        private set

    private fun persist() {
        prefs.edit()
            .putLong("read_counter", readCounter)
            .putLong("write_counter", writeCounter)
            .putInt("reboot_counter", rebootCounter)
            .apply()
    }

    /**
     * Cifra un payload para enviarlo a la bomba.
     *
     * Proceso:
     * 1. Genera un nonce aleatorio de 24 bytes.
     * 2. Añade los contadores al plaintext: `payload || reboot_counter(4B LE) || write_counter(8B LE)`.
     * 3. Incrementa [writeCounter] antes de cifrar.
     * 4. Cifra con XChaCha20-Poly1305 usando la [sharedKey].
     * 5. Persiste los contadores en [SharedPreferences].
     *
     * Formato del resultado: `ciphertext+tag(16B) || nonce(24B)`.
     *
     * @param payload Bytes del comando a cifrar (sin contadores).
     * @return Array cifrado listo para ser fragmentado y enviado vía BLE.
     */
    fun encrypt(payload: ByteArray): ByteArray {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }

        val buffer = ByteBuffer.allocate(payload.size + 12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(payload)
        buffer.putInt(rebootCounter)
        writeCounter++
        buffer.putLong(writeCounter)

        val ciphertext = YpsoCrypto.xchacha20Poly1305Encrypt(
            buffer.array(), byteArrayOf(), nonce, sharedKey
        )
        persist()
        return ciphertext + nonce
    }

    /**
     * Descifra una respuesta recibida de la bomba y sincroniza los contadores.
     *
     * Proceso:
     * 1. Extrae el nonce de los últimos 24 bytes de [data].
     * 2. Descifra el resto con XChaCha20-Poly1305.
     * 3. Lee los 12 bytes de contadores al final del plaintext:
     *    `reboot_counter(4B LE) || numeric_counter(8B LE)`.
     * 4. Si el `reboot_counter` de la bomba cambió, actualiza [rebootCounter] y
     *    reinicia [writeCounter] a 0.
     * 5. Actualiza [readCounter] con el contador numérico de la bomba.
     * 6. Persiste los contadores.
     *
     * @param data Bytes cifrados recibidos de la bomba (mínimo 40 bytes: 16 tag + 24 nonce).
     * @return Plaintext descifrado sin los 12 bytes de contadores al final.
     * @throws IllegalArgumentException Si [data] es demasiado corto.
     * @throws javax.crypto.AEADBadTagException Si el tag de autenticación no es válido
     *         (indica clave incorrecta o datos corruptos).
     */
    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 24 + 16) throw IllegalArgumentException("Encrypted payload too short")

        val nonce = data.copyOfRange(data.size - 24, data.size)
        val ciphertext = data.copyOfRange(0, data.size - 24)

        val plaintext = YpsoCrypto.xchacha20Poly1305Decrypt(
            ciphertext, byteArrayOf(), nonce, sharedKey
        )

        val counters = ByteBuffer.wrap(plaintext, plaintext.size - 12, 12)
            .order(ByteOrder.LITTLE_ENDIAN)
        val peerRebootCounter = counters.int
        val numericCounter = counters.long

        if (peerRebootCounter != rebootCounter) {
            rebootCounter = peerRebootCounter
            writeCounter = 0
        }
        readCounter = numericCounter
        persist()

        return plaintext.copyOfRange(0, plaintext.size - 12)
    }

    companion object {
        /**
         * Carga un [PumpCryptor] desde las [SharedPreferences] si existe una clave válida y no expirada.
         *
         * @param context Contexto Android para acceder a las preferencias.
         * @return Un [PumpCryptor] inicializado, o `null` si no hay clave guardada o ha caducado.
         */
        fun fromPrefs(context: Context): PumpCryptor? {
            val prefs = context.getSharedPreferences("ypso_crypto", Context.MODE_PRIVATE)
            val keyHex = prefs.getString("shared_key", null) ?: return null
            val expiresAt = prefs.getLong("shared_key_expires_at", Long.MAX_VALUE)
            if (System.currentTimeMillis() > expiresAt) return null
            return PumpCryptor(keyHex.hexToBytes(), prefs)
        }

        /**
         * Crea un nuevo [PumpCryptor] con la clave compartida proporcionada y reinicia
         * todos los contadores a 0. Persiste la clave y los contadores en [SharedPreferences].
         *
         * La clave se almacena con una fecha de expiración de 3650 días a partir del momento
         * de creación (comportamiento actual; la validez real del protocolo es de 28 días).
         *
         * @param context Contexto Android para acceder a las preferencias.
         * @param sharedKey Clave compartida de 32 bytes derivada durante el intercambio de claves.
         * @return Un nuevo [PumpCryptor] listo para usarse.
         */
        fun create(context: Context, sharedKey: ByteArray): PumpCryptor {
            val prefs = context.getSharedPreferences("ypso_crypto", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("shared_key", sharedKey.toHexString())
                .putLong("shared_key_expires_at", System.currentTimeMillis() + 3650L * 24 * 3600 * 1000)
                .putLong("read_counter", 0)
                .putLong("write_counter", 0)
                .putInt("reboot_counter", 0)
                .apply()
            return PumpCryptor(sharedKey, prefs)
        }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun ByteArray.toHexString(): String =
            joinToString("") { "%02x".format(it) }
    }
}
