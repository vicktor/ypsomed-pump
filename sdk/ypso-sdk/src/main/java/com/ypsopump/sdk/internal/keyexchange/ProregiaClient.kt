package com.ypsopump.sdk.internal.keyexchange

import android.util.Log
import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Cliente gRPC para el servidor de intercambio de claves Proregia.
 *
 * Se comunica con `connect.ml.pr.sec01.proregia.io:8090` usando gRPC sobre TLS (OkHttp).
 * En lugar de usar generación de código protobuf, los mensajes se codifican y decodifican
 * manualmente con las funciones de varint y campos definidas internamente.
 *
 * ## Métodos gRPC expuestos
 * - `Proregia.Bluetooth.Contracts.Proto.NonceRequest/Send`: obtiene un nonce de servidor.
 * - `Proregia.Bluetooth.Contracts.Proto.EncryptKey/Send`: solicita el payload cifrado
 *   que se enviará a la bomba durante el intercambio de claves.
 *
 * ## Codificación protobuf manual
 * No se usa ninguna herramienta de generación de código (protoc, Wire, etc.).
 * Los mensajes se construyen byte a byte usando helpers privados:
 * [encodeVarint], [pbTag], [pbString], [pbBool], [pbMessage].
 * La respuesta se decodifica con [decodeFirstStringField].
 *
 * Cada llamada crea y cierra su propio canal gRPC (no se reutilizan conexiones).
 */
internal class ProregiaClient {

    companion object {
        private const val TAG = "ProregiaClient"
        private const val SERVER_HOST = "connect.ml.pr.sec01.proregia.io"
        private const val SERVER_PORT = 8090
    }

    // ==================== Protobuf Encoding Helpers ====================

    private fun encodeVarint(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val result = mutableListOf<Byte>()
        var v = value
        while (v != 0L) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            result.add(if (v != 0L) (b or 0x80).toByte() else b.toByte())
        }
        return result.toByteArray()
    }

    private fun pbTag(fieldNumber: Int, wireType: Int): ByteArray =
        encodeVarint(((fieldNumber shl 3) or wireType).toLong())

    private fun pbString(fieldNumber: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return pbTag(fieldNumber, 2) + encodeVarint(bytes.size.toLong()) + bytes
    }

    private fun pbBool(fieldNumber: Int, value: Boolean): ByteArray =
        pbTag(fieldNumber, 0) + byteArrayOf(if (value) 1 else 0)

    private fun pbMessage(fieldNumber: Int, message: ByteArray): ByteArray =
        pbTag(fieldNumber, 2) + encodeVarint(message.size.toLong()) + message

    // ==================== Protobuf Decoding ====================

    private fun decodeFirstStringField(data: ByteArray): String {
        var pos = 0
        while (pos < data.size) {
            var tag = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos].toInt() and 0xFF
                pos++
                tag = tag or ((b.toLong() and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (wireType) {
                0 -> {
                    while (pos < data.size && (data[pos].toInt() and 0x80) != 0) pos++
                    pos++
                }
                2 -> {
                    var len = 0L
                    shift = 0
                    while (pos < data.size) {
                        val b = data[pos].toInt() and 0xFF
                        pos++
                        len = len or ((b.toLong() and 0x7F) shl shift)
                        shift += 7
                        if (b and 0x80 == 0) break
                    }
                    if (fieldNumber == 1) {
                        return String(data, pos, len.toInt(), Charsets.UTF_8)
                    }
                    pos += len.toInt()
                }
                else -> throw IllegalStateException("Unsupported wire type: $wireType at field $fieldNumber")
            }
        }
        throw IllegalStateException("String field 1 not found in protobuf response")
    }

    // ==================== Message Builders ====================

    private fun encodeMetrics(): ByteArray =
        pbString(1, "Android") +
        pbString(2, "M2011K2G") +
        pbString(3, "Phone") +
        pbString(4, "13") +
        pbString(5, "Xiaomi") +
        pbString(6, "na") +
        pbString(7, "mylife app") +
        pbString(8, "net.sinovo.mylife.app") +
        pbString(9, "1.0.0.0") +
        pbBool(10, true)

    private fun encodeDeviceIdentifier(deviceId: String, btAddressHex: String): ByteArray =
        pbString(1, deviceId) +
        pbString(2, btAddressHex) +
        pbMessage(3, encodeMetrics())

    private fun encodeEncryptKeyRequest(
        challenge: String,
        pumpPublicKey: String,
        appPublicKey: String,
        btAddress: String,
        playIntegrityToken: String,
        nonce: String
    ): ByteArray =
        pbString(1, challenge) +
        pbString(2, pumpPublicKey) +
        pbString(3, appPublicKey) +
        pbString(4, btAddress) +
        pbString(5, playIntegrityToken) +
        pbString(6, nonce) +
        pbMessage(7, encodeMetrics())

    // ==================== gRPC Transport ====================

    private val byteArrayMarshaller = object : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }

    private fun unaryMethod(fullMethodName: String): MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor.newBuilder(byteArrayMarshaller, byteArrayMarshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(fullMethodName)
            .build()

    private fun createChannel() = OkHttpChannelBuilder
        .forAddress(SERVER_HOST, SERVER_PORT)
        .useTransportSecurity()
        .build()

    // ==================== Public API ====================

    /**
     * Solicita un nonce de servidor al endpoint `NonceRequest/Send` de Proregia.
     *
     * Envía un mensaje protobuf con el identificador de dispositivo (ID de app + dirección BT +
     * métricas del dispositivo) y devuelve el nonce como array de bytes decodificado desde hex.
     *
     * El nonce de servidor se usa posteriormente como campo de la petición [encryptKey] para
     * prevenir ataques de repetición en el intercambio de claves.
     *
     * @param btAddress 6 bytes de la dirección Bluetooth de la bomba.
     * @param deviceId Identificador único del dispositivo (UUID generado en primer uso).
     * @return Array de bytes con el nonce de servidor (típicamente 32 bytes).
     * @throws io.grpc.StatusRuntimeException Si la llamada gRPC falla.
     * @throws IllegalStateException Si la respuesta protobuf no contiene el campo string esperado.
     */
    fun getNonce(btAddress: ByteArray, deviceId: String): ByteArray {
        Log.d(TAG, "getNonce: btAddress=${btAddress.hex()}, deviceId=$deviceId")
        val channel = createChannel()
        try {
            val request = encodeDeviceIdentifier(deviceId, btAddress.hex())
            val method = unaryMethod("Proregia.Bluetooth.Contracts.Proto.NonceRequest/Send")
            val response = ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, request)
            val nonceHex = decodeFirstStringField(response)
            Log.d(TAG, "Server nonce (${nonceHex.length / 2} bytes): $nonceHex")
            return nonceHex.hexToBytes()
        } finally {
            channel.shutdownNow()
        }
    }

    /**
     * Solicita el payload cifrado al endpoint `EncryptKey/Send` de Proregia.
     *
     * Envía todos los parámetros del intercambio de claves al servidor Proregia, que:
     * 1. Verifica el token de Play Integrity para confirmar la autenticidad del dispositivo.
     * 2. Construye y cifra el payload de respuesta al desafío de la bomba.
     * 3. Devuelve el payload cifrado en hex que debe escribirse en [YpsoPumpUuids.CHAR_CMD_WRITE].
     *
     * Todos los campos binarios (challenge, claves, dirección BT, nonce) se envían
     * como cadenas hexadecimales en mayúsculas dentro del mensaje protobuf.
     *
     * @param btAddress 6 bytes de la dirección Bluetooth de la bomba.
     * @param serverNonce Nonce de servidor obtenido previamente con [getNonce].
     * @param challenge 32 bytes del desafío leído de la bomba vía BLE.
     * @param pumpPublicKey 32 bytes de la clave pública X25519 de la bomba.
     * @param appPublicKey 32 bytes de la clave pública X25519 del dispositivo.
     * @param playIntegrityToken Token de Google Play Integrity válido.
     * @return Array de bytes con el payload cifrado que debe enviarse a la bomba.
     * @throws io.grpc.StatusRuntimeException Si la llamada gRPC falla.
     * @throws IllegalStateException Si la respuesta protobuf no contiene el campo string esperado.
     */
    fun encryptKey(
        btAddress: ByteArray,
        serverNonce: ByteArray,
        challenge: ByteArray,
        pumpPublicKey: ByteArray,
        appPublicKey: ByteArray,
        playIntegrityToken: String
    ): ByteArray {
        Log.d(TAG, "encryptKey: challenge=${challenge.hex()}")
        val channel = createChannel()
        try {
            val challengeHex = challenge.hexUpper()
            val pumpPubHex = pumpPublicKey.hexUpper()
            val appPubHex = appPublicKey.hexUpper()
            val btAddrHex = btAddress.hexUpper()
            val nonceHex = serverNonce.hexUpper()
            Log.d(TAG, "Fields: challenge=$challengeHex")
            Log.d(TAG, "Fields: pump_public_key=$pumpPubHex")
            Log.d(TAG, "Fields: app_public_key=$appPubHex")
            Log.d(TAG, "Fields: bt_address=$btAddrHex")
            Log.d(TAG, "Fields: token_len=${playIntegrityToken.length}")
            Log.d(TAG, "Fields: nonce=$nonceHex")
            val request = encodeEncryptKeyRequest(
                challenge = challengeHex,
                pumpPublicKey = pumpPubHex,
                appPublicKey = appPubHex,
                btAddress = btAddrHex,
                playIntegrityToken = playIntegrityToken,
                nonce = nonceHex
            )
            Log.d(TAG, "Request size: ${request.size} bytes")
            Log.d(TAG, "Request first 100 bytes: ${request.take(100).toByteArray().hex()}")
            val method = unaryMethod("Proregia.Bluetooth.Contracts.Proto.EncryptKey/Send")
            val response = ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, request)
            val encryptedHex = decodeFirstStringField(response)
            val result = encryptedHex.hexToBytes()
            Log.d(TAG, "Encrypted payload: ${result.size} bytes")
            return result
        } finally {
            channel.shutdownNow()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun ByteArray.hexUpper(): String = joinToString("") { "%02X".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
