package com.ypsopump.sdk.internal.keyexchange

import android.content.Context
import android.util.Log
import com.ypsopump.sdk.internal.ble.YpsoBleManager
import com.ypsopump.sdk.internal.ble.YpsoPumpUuids
import com.ypsopump.sdk.internal.crypto.PumpCryptor
import com.ypsopump.sdk.internal.crypto.YpsoCrypto
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

/**
 * Orquesta el flujo completo de intercambio de claves con la bomba YpsoPump.
 *
 * ## Flujo de intercambio de claves
 * 1. **Leer clave pública de la bomba**: se leen 64 bytes de [YpsoPumpUuids.CHAR_CMD_READ_A]:
 *    - Bytes 0–31: challenge (desafío criptográfico de la bomba).
 *    - Bytes 32–63: clave pública X25519 de la bomba.
 * 2. **Obtener nonce del servidor**: se llama a [ProregiaClient.getNonce] con la dirección BT
 *    y el ID de dispositivo para obtener un nonce de servidor de 32 bytes.
 * 3. **Obtener token Play Integrity**: el llamador debe proporcionar un token válido obtenido
 *    mediante [PlayIntegrityHelper], que Proregia usa para verificar la autenticidad del dispositivo.
 * 4. **Llamar a EncryptKey**: se invoca [ProregiaClient.encryptKey] con el challenge, las claves
 *    públicas, la dirección BT, el token Play Integrity y el nonce. El servidor devuelve
 *    el payload cifrado que debe enviarse a la bomba.
 * 5. **Escribir respuesta a la bomba**: el payload cifrado se envía vía multi-trama a
 *    [YpsoPumpUuids.CHAR_CMD_WRITE].
 * 6. **Derivar clave compartida**: se calcula `HChaCha20(X25519(privApp, pubBomba), zeros_16B)`
 *    y se crea un [PumpCryptor] con la clave resultante.
 *
 * El par de claves X25519 del dispositivo se persiste en [SharedPreferences] para reutilizarse
 * en futuros intercambios sin necesidad de regenerarlo.
 *
 * @param context Contexto Android para acceder a las preferencias.
 * @param bleManager Gestor BLE para leer/escribir características de la bomba.
 */
internal class YpsoKeyExchange(
    private val context: Context,
    private val bleManager: YpsoBleManager
) {
    companion object {
        private const val TAG = "YpsoKeyExchange"
        private const val PREFS_NAME = "ypso_key_exchange"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val proregiaClient = ProregiaClient()

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
                Log.d(TAG, "Generated new device ID: $id")
            }
            return id
        }

    /**
     * Devuelve el par de claves X25519 del dispositivo, cargándolo desde las preferencias
     * si ya existe o generando uno nuevo si no.
     *
     * El par de claves se persiste en [SharedPreferences] bajo las entradas
     * `x25519_priv_pkcs8` (clave privada en formato PKCS#8 hex) y
     * `x25519_pub_raw` (clave pública raw de 32 bytes en hex).
     *
     * @return Par de claves X25519 listo para usarse en el intercambio de claves.
     */
    fun getOrCreateKeyPair(): YpsoCrypto.X25519KeyPair {
        val privHex = prefs.getString("x25519_priv_pkcs8", null)
        val pubHex = prefs.getString("x25519_pub_raw", null)

        if (privHex != null && pubHex != null) {
            try {
                val privBytes = privHex.hexToBytes()
                val pubBytes = pubHex.hexToBytes()
                val kf = KeyFactory.getInstance("X25519")
                val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val publicKey = YpsoCrypto.rawBytesToPublicKey(pubBytes)
                Log.d(TAG, "Loaded existing X25519 key pair")
                return YpsoCrypto.X25519KeyPair(privateKey, publicKey, pubBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load key pair, generating new: ${e.message}")
            }
        }

        val keyPair = YpsoCrypto.generateX25519KeyPair()
        prefs.edit()
            .putString("x25519_priv_pkcs8", keyPair.privateKey.encoded.toHexString())
            .putString("x25519_pub_raw", keyPair.rawPublicKey.toHexString())
            .apply()
        Log.d(TAG, "Generated new X25519 key pair")
        return keyPair
    }

    /**
     * Ejecuta el flujo completo de intercambio de claves de forma asíncrona.
     *
     * Las llamadas de red a Proregia se realizan en un hilo separado. Los callbacks
     * de BLE se invocan en el hilo de BLE (según la implementación de [bleManager]).
     *
     * @param serial Número de serie de la bomba (p. ej. "10175983").
     * @param playIntegrityToken Token de Google Play Integrity obtenido previamente
     *        mediante [PlayIntegrityHelper.requestToken].
     * @param onProgress Callback invocado con mensajes de progreso durante el proceso.
     * @param onComplete Callback final con [Result.success] conteniendo el [PumpCryptor]
     *        si el intercambio fue exitoso, o [Result.failure] con la excepción si falló.
     */
    fun performKeyExchange(
        serial: String,
        playIntegrityToken: String,
        onProgress: (String) -> Unit,
        onComplete: (Result<PumpCryptor>) -> Unit
    ) {
        val keyPair = getOrCreateKeyPair()
        val btAddress = YpsoCrypto.serialToBtAddress(serial)

        Log.d(TAG, "Starting key exchange for serial=$serial")
        Log.d(TAG, "App public key: ${keyPair.rawPublicKey.toHexString()}")
        Log.d(TAG, "BT address: ${btAddress.toHexString()}")
        onProgress("Reading pump public key...")

        bleManager.readExtended(YpsoPumpUuids.CHAR_CMD_READ_A) { data ->
            if (data == null || data.size < 64) {
                val msg = "Failed to read pump public key (got ${data?.size ?: 0} bytes, need 64)"
                Log.e(TAG, msg)
                onComplete(Result.failure(Exception(msg)))
                return@readExtended
            }

            val challenge = data.copyOfRange(0, 32)
            val pumpPublicKey = data.copyOfRange(32, 64)

            Log.d(TAG, "Challenge (32B): ${challenge.toHexString()}")
            Log.d(TAG, "Pump public key (32B): ${pumpPublicKey.toHexString()}")
            onProgress("Got pump public key and challenge")

            Thread {
                try {
                    onProgress("Getting server nonce...")
                    val serverNonce = proregiaClient.getNonce(btAddress, deviceId)
                    Log.d(TAG, "Server nonce (${serverNonce.size}B): ${serverNonce.toHexString()}")
                    onProgress("Server nonce received (${serverNonce.size} bytes)")

                    onProgress("Calling EncryptKey...")
                    val encryptedBytes = proregiaClient.encryptKey(
                        btAddress = btAddress,
                        serverNonce = serverNonce,
                        challenge = challenge,
                        pumpPublicKey = pumpPublicKey,
                        appPublicKey = keyPair.rawPublicKey,
                        playIntegrityToken = playIntegrityToken
                    )
                    Log.d(TAG, "Encrypted payload: ${encryptedBytes.size} bytes")
                    onProgress("Encrypted payload received (${encryptedBytes.size} bytes)")

                    onProgress("Writing challenge response to pump...")
                    bleManager.writeMultiFrame(
                        YpsoPumpUuids.CHAR_CMD_WRITE,
                        encryptedBytes
                    ) { success ->
                        if (!success) {
                            val msg = "Failed to write challenge response to pump"
                            Log.e(TAG, msg)
                            onComplete(Result.failure(Exception(msg)))
                            return@writeMultiFrame
                        }

                        Log.d(TAG, "Challenge response written successfully")
                        onProgress("Challenge response written")

                        try {
                            val sharedKey = YpsoCrypto.deriveSharedKey(
                                keyPair.privateKey, pumpPublicKey
                            )
                            Log.d(TAG, "Shared key derived: ${sharedKey.toHexString()}")

                            val cryptor = PumpCryptor.create(context, sharedKey)
                            onProgress("Key exchange complete! Shared key established.")
                            onComplete(Result.success(cryptor))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to derive shared key", e)
                            onComplete(Result.failure(e))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Key exchange failed", e)
                    onProgress("Key exchange failed: ${e.message}")
                    onComplete(Result.failure(e))
                }
            }.start()
        }
    }

    /**
     * Indica si ya existe una clave compartida válida guardada en las preferencias.
     *
     * @return `true` si hay una clave almacenada y no ha caducado, `false` en caso contrario.
     */
    fun hasValidSharedKey(): Boolean = PumpCryptor.fromPrefs(context) != null

    /**
     * Carga el [PumpCryptor] guardado en las preferencias si existe y está vigente.
     *
     * @return El [PumpCryptor] existente, o `null` si no hay clave válida.
     */
    fun loadCryptor(): PumpCryptor? = PumpCryptor.fromPrefs(context)

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
