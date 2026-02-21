package com.ypsopump.sdk.internal.protocol

/**
 * Implementación de CRC16 para el protocolo YpsoPump.
 * Portada desde Uneo7/Ypso crc.py.
 *
 * Utiliza el polinomio CRC-32 0x04C11DB7 con bitstuffing,
 * devolviendo los 16 bits inferiores como 2 bytes en formato little-endian.
 *
 * El proceso es:
 * 1. Los datos de entrada se reorganizan en bloques de 4 bytes invertidos (bitstuffing).
 * 2. Se calcula el CRC-32 sobre los datos reorganizados.
 * 3. Se devuelven los 2 bytes menos significativos del resultado en orden LE.
 *
 * Este CRC se añade al final de cada trama antes del cifrado y se verifica
 * tras el descifrado para garantizar la integridad del payload.
 */
internal object YpsoCrc {

    private const val CRC_POLY = 0x04C11DB7L

    private val CRC_TABLE = LongArray(256).also { table ->
        for (idx in 0 until 256) {
            var v = idx.toLong() shl 24
            for (bit in 0 until 8) {
                v = if (v and 0x80000000L != 0L) {
                    ((v shl 1) and 0xFFFFFFFFL) xor CRC_POLY
                } else {
                    (v shl 1) and 0xFFFFFFFFL
                }
            }
            table[idx] = v
        }
    }

    private fun bitstuff(data: ByteArray): ByteArray {
        if (data.isEmpty()) return byteArrayOf()
        val blockCount = (data.size + 3) / 4
        val stuffed = ByteArray(blockCount * 4)
        for (block in 0 until blockCount) {
            val base = block * 4
            for (idx in 0 until 4) {
                val src = base + idx
                stuffed[base + 3 - idx] = if (src < data.size) data[src] else 0
            }
        }
        return stuffed
    }

    /**
     * Calcula el CRC16 de un array de bytes.
     *
     * Primero reorganiza los datos en bloques de 4 bytes invertidos (bitstuffing),
     * luego aplica el algoritmo CRC-32 con polinomio 0x04C11DB7 y devuelve
     * los 16 bits menos significativos del resultado en formato little-endian.
     *
     * @param payload Datos de entrada sobre los que calcular el CRC.
     * @return Array de 2 bytes con el CRC16 en formato little-endian.
     */
    fun crc16(payload: ByteArray): ByteArray {
        var crc = 0xFFFFFFFFL
        for (byte in bitstuff(payload)) {
            val tableIdx = ((crc shr 24) xor (byte.toLong() and 0xFF)) and 0xFF
            crc = ((crc shl 8) and 0xFFFFFFFFL) xor CRC_TABLE[tableIdx.toInt()]
        }
        val result = (crc and 0xFFFFL).toInt()
        return byteArrayOf(
            (result and 0xFF).toByte(),
            ((result shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Verifica que los últimos 2 bytes de [payload] sean un CRC16 válido
     * del resto del contenido.
     *
     * @param payload Array de bytes donde los 2 últimos bytes son el CRC esperado.
     * @return `true` si el CRC coincide, `false` si el payload tiene menos de 2 bytes
     *         o si el CRC no es válido.
     */
    fun isValid(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val data = payload.copyOfRange(0, payload.size - 2)
        val crcBytes = payload.copyOfRange(payload.size - 2, payload.size)
        return crc16(data).contentEquals(crcBytes)
    }

    /**
     * Añade el CRC16 al final de [payload] y devuelve el resultado concatenado.
     *
     * El array resultante tiene `payload.size + 2` bytes: el payload original
     * seguido de los 2 bytes de CRC en formato little-endian.
     *
     * @param payload Datos a los que añadir el CRC.
     * @return Nuevo array con el payload original más el CRC16 al final.
     */
    fun appendCrc(payload: ByteArray): ByteArray {
        return payload + crc16(payload)
    }
}
