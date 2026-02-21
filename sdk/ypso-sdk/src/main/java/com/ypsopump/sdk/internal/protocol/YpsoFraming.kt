package com.ypsopump.sdk.internal.protocol

/**
 * Protocolo de tramas múltiples BLE para YpsoPump.
 * Portado desde Uneo7/Ypso utils.py.
 *
 * Cada escritura BLE tiene un máximo de 20 bytes: 1 byte de cabecera + 19 bytes de payload.
 * Formato del byte de cabecera: `((frame_idx+1) << 4 & 0xF0) | (total_frames & 0x0F)`
 *
 * - Los 4 bits altos indican el número de trama actual (base 1).
 * - Los 4 bits bajos indican el número total de tramas.
 *
 * Para mensajes que caben en una sola trama (≤ 19 bytes), la cabecera es 0x11.
 * Para payloads vacíos se envía una trama mínima con cabecera 0x10.
 */
internal object YpsoFraming {

    private const val MAX_PAYLOAD_PER_FRAME = 19

    /**
     * Divide un payload en una lista de tramas BLE listas para ser enviadas.
     *
     * Cada trama incluye un byte de cabecera que codifica el índice de la trama
     * (base 1, en los 4 bits altos) y el número total de tramas (en los 4 bits bajos).
     * El payload se parte en chunks de hasta 19 bytes.
     *
     * Si [data] está vacío, se devuelve una sola trama con cabecera 0x10.
     *
     * @param data Bytes del payload a fragmentar.
     * @return Lista de tramas, cada una con su byte de cabecera prepended.
     */
    fun chunkPayload(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) {
            return listOf(byteArrayOf(0x10))
        }

        val totalFrames = maxOf(1, (data.size + MAX_PAYLOAD_PER_FRAME - 1) / MAX_PAYLOAD_PER_FRAME)
        val frames = mutableListOf<ByteArray>()

        for (idx in 0 until totalFrames) {
            val start = idx * MAX_PAYLOAD_PER_FRAME
            val end = minOf(start + MAX_PAYLOAD_PER_FRAME, data.size)
            val chunk = data.copyOfRange(start, end)

            val header = (((idx + 1) shl 4) and 0xF0) or (totalFrames and 0x0F)
            frames.add(byteArrayOf(header.toByte()) + chunk)
        }

        return frames
    }

    /**
     * Reconstituye el payload original a partir de una lista de tramas recibidas.
     *
     * Elimina el byte de cabecera de cada trama y concatena el resto en orden.
     * Las tramas de 1 solo byte (solo cabecera, sin payload) se ignoran.
     *
     * @param frames Lista de tramas recibidas, cada una con su byte de cabecera.
     * @return Array de bytes con el payload reconstituido.
     */
    fun parseMultiFrameRead(frames: List<ByteArray>): ByteArray {
        val merged = mutableListOf<Byte>()
        for (frame in frames) {
            if (frame.size > 1) {
                merged.addAll(frame.drop(1))
            }
        }
        return merged.toByteArray()
    }

    /**
     * Extrae el número total de tramas del byte de cabecera de la primera trama.
     *
     * Los 4 bits bajos del byte de cabecera indican cuántas tramas componen
     * el mensaje completo. Si el valor es 0 (trama vacía), se devuelve 1.
     *
     * @param firstByte Byte de cabecera de la primera trama recibida.
     * @return Número total de tramas esperadas (mínimo 1).
     */
    fun getTotalFrames(firstByte: Byte): Int {
        return (firstByte.toInt() and 0x0F).let { if (it == 0) 1 else it }
    }
}
