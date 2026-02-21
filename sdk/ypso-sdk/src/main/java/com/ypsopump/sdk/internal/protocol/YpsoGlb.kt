package com.ypsopump.sdk.internal.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Codificación/decodificación de variables seguras GLB para el protocolo YpsoPump.
 *
 * Una variable GLB ocupa 8 bytes: el valor original (u32 LE) seguido de su complemento
 * a nivel de bits (NOT bit a bit, u32 LE). Esto permite detectar corrupción de datos
 * verificando que `valor XOR complemento == 0xFFFFFFFF`.
 *
 * Se usa para escribir índices de configuración (SETTING_ID), para leer/escribir
 * contadores de historial y para codificar parámetros de TBR y bolo.
 */
internal object YpsoGlb {
    /**
     * Codifica un entero como variable segura GLB de 8 bytes.
     *
     * El resultado contiene el valor en los primeros 4 bytes (u32 LE) y el
     * complemento bit a bit del valor en los siguientes 4 bytes (u32 LE).
     *
     * @param value Valor entero a codificar (se trata como u32).
     * @return Array de 8 bytes con la codificación GLB.
     */
    fun encode(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        buf.putInt(value.inv())
        return buf.array()
    }

    /**
     * Decodifica una variable segura GLB de 8 bytes y devuelve el valor entero.
     *
     * Verifica la integridad comprobando que el primer u32 LE y el segundo u32 LE
     * son complementos bit a bit el uno del otro. Lanza [IllegalArgumentException]
     * si los datos son insuficientes o si la comprobación falla.
     *
     * @param data Array de al menos 8 bytes con la codificación GLB.
     * @return El valor entero decodificado.
     * @throws IllegalArgumentException Si los datos tienen menos de 8 bytes o
     *         si la verificación de integridad GLB falla.
     */
    fun decode(data: ByteArray): Int {
        require(data.size >= 8) { "GLB data must be at least 8 bytes, got ${data.size}" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val value = buf.int
        val check = buf.int
        require(value == check.inv()) {
            "GLB safe variable integrity check failed: $value vs ${check.inv()}"
        }
        return value
    }

    /**
     * Busca la primera variable GLB válida en un payload arbitrario.
     *
     * Recorre el array byte a byte intentando leer un GLB válido en cada posición.
     * Útil cuando el offset de la variable GLB dentro de una respuesta es desconocido
     * o cuando el payload contiene datos adicionales antes o después del GLB.
     *
     * @param data Array de bytes en el que buscar.
     * @return El valor del primer GLB válido encontrado, o `null` si no hay ninguno.
     */
    fun findInPayload(data: ByteArray): Int? {
        if (data.size < 8) return null
        for (start in 0..data.size - 8) {
            val buf = ByteBuffer.wrap(data, start, 8).order(ByteOrder.LITTLE_ENDIAN)
            val value = buf.int
            val check = buf.int
            if (value == check.inv()) return value
        }
        return null
    }
}
