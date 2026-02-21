package com.ypsopump.sdk.internal.keyexchange

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP alternativo para el intercambio de claves mediante un servidor relay.
 *
 * En lugar de comunicarse directamente con Proregia vía gRPC ([ProregiaClient]),
 * este cliente envía los parámetros del intercambio de claves a un servidor relay HTTP
 * intermediario que actúa de proxy hacia Proregia. Esto permite realizar el intercambio
 * desde entornos donde gRPC no está disponible o donde se prefiere delegar la lógica
 * de Play Integrity al servidor relay.
 *
 * ## Protocolo
 * - Método: `POST {baseUrl}/key-exchange`
 * - Cuerpo: JSON con los campos `challenge`, `pump_public_key`, `app_public_key`,
 *   `bt_address` y `device_id`, todos como cadenas hexadecimales en minúsculas.
 * - Respuesta: JSON con `encrypted_bytes` y `server_nonce` en hex.
 *
 * El servidor relay se encarga de obtener el nonce de Proregia, solicitar el token
 * Play Integrity y llamar a `EncryptKey/Send` de forma transparente.
 *
 * Timeout de conexión y lectura: 120 segundos.
 */
internal class RelayClient {

    companion object {
        private const val TAG = "RelayClient"
    }

    data class RelayResponse(
        val encryptedBytes: ByteArray,
        val serverNonce: ByteArray
    )

    /**
     * Ejecuta el intercambio de claves completo a través del servidor relay.
     *
     * Realiza una petición `POST {baseUrl}/key-exchange` con los parámetros del
     * intercambio en formato JSON y devuelve el payload cifrado y el nonce de servidor.
     *
     * La llamada es bloqueante y debe ejecutarse en un hilo/coroutine fuera del hilo principal.
     *
     * @param baseUrl URL base del servidor relay (sin barra final, p. ej. `"https://relay.example.com"`).
     * @param challenge 32 bytes del desafío leído de la bomba vía BLE.
     * @param pumpPublicKey 32 bytes de la clave pública X25519 de la bomba.
     * @param appPublicKey 32 bytes de la clave pública X25519 del dispositivo.
     * @param btAddress 6 bytes de la dirección Bluetooth de la bomba.
     * @param deviceId Identificador único del dispositivo.
     * @return [RelayResponse] con el payload cifrado y el nonce de servidor, ambos como arrays de bytes.
     * @throws RuntimeException Si el servidor devuelve un código HTTP distinto de 200.
     */
    fun callRelayServer(
        baseUrl: String,
        challenge: ByteArray,
        pumpPublicKey: ByteArray,
        appPublicKey: ByteArray,
        btAddress: ByteArray,
        deviceId: String
    ): RelayResponse {
        val url = URL("$baseUrl/key-exchange")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 120_000
        conn.readTimeout = 120_000
        conn.doOutput = true

        val body = JSONObject().apply {
            put("challenge", challenge.hex())
            put("pump_public_key", pumpPublicKey.hex())
            put("app_public_key", appPublicKey.hex())
            put("bt_address", btAddress.hex())
            put("device_id", deviceId)
        }

        Log.d(TAG, "POST $url")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code != 200) {
            val errorBody = try {
                conn.errorStream?.bufferedReader()?.readText() ?: "no body"
            } catch (_: Exception) { "unreadable" }
            throw RuntimeException("Relay returned HTTP $code: $errorBody")
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)
        val encryptedHex = json.getString("encrypted_bytes")
        val nonceHex = json.getString("server_nonce")

        return RelayResponse(
            encryptedBytes = encryptedHex.hexToBytes(),
            serverNonce = nonceHex.hexToBytes()
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
