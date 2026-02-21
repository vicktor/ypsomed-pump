package com.ypsopump.sdk.internal.data

import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.EventType
import com.ypsopump.sdk.internal.protocol.YpsoCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Datos de estado del sistema de la bomba, obtenidos descifrando la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_SYSTEM_STATUS] (`fcbee48b7bc5`).
 *
 * @property deliveryMode Modo de entrega actual (ver [com.ypsopump.sdk.internal.protocol.DeliveryMode]).
 * @property deliveryModeName Nombre legible del modo de entrega actual.
 * @property insulinRemaining Insulina restante en el reservorio, en unidades (U).
 * @property batteryPercent Nivel de batería actual, en porcentaje (0–100).
 */
internal data class SystemStatusData(
    val deliveryMode: Int,
    val deliveryModeName: String,
    val insulinRemaining: Float,
    val batteryPercent: Int
)

/**
 * Estado detallado del bolo en curso, leído de la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_BOLUS_STATUS] (`fcbee28b7bc5`).
 *
 * Esta característica es cifrada y devuelve hasta 42+ bytes con información del
 * bolo rápido (inmediato) y del bolo lento (extendido) simultáneamente.
 *
 * Los valores de insulina están en unidades (U), convertidos desde centiunidades.
 *
 * @property fastStatus Estado del bolo rápido (ver [com.ypsopump.sdk.internal.protocol.BolusNotificationStatus]).
 * @property fastSequence Número de secuencia del bolo rápido.
 * @property fastInjected Unidades de bolo rápido ya inyectadas.
 * @property fastTotal Unidades totales programadas para el bolo rápido.
 * @property slowStatus Estado del bolo extendido.
 * @property slowSequence Número de secuencia del bolo extendido.
 * @property slowInjected Unidades de bolo extendido ya inyectadas.
 * @property slowTotal Unidades totales programadas para el bolo extendido.
 * @property slowFastPartInjected Unidades de la parte rápida del bolo extendido ya inyectadas.
 * @property slowFastPartTotal Unidades totales de la parte rápida del bolo extendido.
 * @property actualDuration Duración transcurrida del bolo extendido, en minutos.
 * @property totalDuration Duración total programada del bolo extendido, en minutos.
 */
internal data class BolusStatusData(
    val fastStatus: Int,
    val fastSequence: Long,
    val fastInjected: Float,
    val fastTotal: Float,
    val slowStatus: Int,
    val slowSequence: Long,
    val slowInjected: Float,
    val slowTotal: Float,
    val slowFastPartInjected: Float,
    val slowFastPartTotal: Float,
    val actualDuration: Int,
    val totalDuration: Int
)

/**
 * Entrada del historial de eventos de la bomba YpsoPump.
 *
 * Cada entrada tiene 17 bytes mínimo y se obtiene leyendo la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.Events.VALUE] tras establecer
 * el índice con [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.Events.INDEX].
 *
 * El timestamp de la bomba usa su propia época (2000-01-01 00:00:00 UTC = 946684800 Unix).
 * La función [parse] convierte automáticamente al timestamp Unix estándar.
 *
 * Los valores `value1`, `value2` y `value3` tienen significados distintos según el tipo
 * de evento. Para eventos de bolo, `value1` y `value2` están en centiunidades
 * (dividir entre 100 para obtener unidades). Para TBR, `value1` es el porcentaje
 * y `value2` es la duración en minutos.
 *
 * @property timestamp Timestamp Unix en segundos (convertido desde la época YpsoPump).
 * @property entryType Identificador del tipo de evento (ver [com.ypsopump.sdk.internal.protocol.EventType]).
 * @property entryTypeName Nombre legible del tipo de evento.
 * @property value1 Primer valor del evento (significado depende del tipo).
 * @property value2 Segundo valor del evento (significado depende del tipo).
 * @property value3 Tercer valor del evento (significado depende del tipo).
 * @property sequence Número de secuencia del evento en la bomba.
 * @property index Índice de la entrada en el historial de la bomba.
 */
internal data class HistoryEntry(
    val timestamp: Long,       // Unix timestamp in seconds (converted from YpsoPump epoch)
    val entryType: Int,
    val entryTypeName: String,
    val value1: Int,
    val value2: Int,
    val value3: Int,
    val sequence: Long,
    val index: Int
) {
    companion object {
        // YpsoPump epoch: 2000-01-01 00:00:00 UTC = 946684800 Unix seconds
        private const val YPSO_EPOCH_OFFSET_SEC = 946684800L

        fun parse(data: ByteArray): HistoryEntry {
            require(data.size >= 17) { "History entry must be at least 17 bytes, got ${data.size}" }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val pumpSeconds = buf.int.toLong() and 0xFFFFFFFFL
            val timestamp = pumpSeconds + YPSO_EPOCH_OFFSET_SEC
            val entryType = buf.get().toInt() and 0xFF
            val value1 = buf.short.toInt() and 0xFFFF
            val value2 = buf.short.toInt() and 0xFFFF
            val value3 = buf.short.toInt() and 0xFFFF
            val sequence = buf.int.toLong() and 0xFFFFFFFFL
            val index = buf.short.toInt() and 0xFFFF
            return HistoryEntry(
                timestamp = timestamp,
                entryType = entryType,
                entryTypeName = EventType.name(entryType),
                value1 = value1,
                value2 = value2,
                value3 = value3,
                sequence = sequence,
                index = index
            )
        }
    }
}

/**
 * Notificación de estado de bolo recibida por BLE desde la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION] (`fcbee58b7bc5`).
 *
 * Esta característica **no está cifrada** y envía 13 bytes con CRC al final (2 bytes).
 * El payload útil es de 10 bytes con el formato:
 * `fast_status(1B) + fast_seq(4B LE) + slow_status(1B) + slow_seq(4B LE)`
 *
 * La función [parse] elimina el CRC si está presente y parsea el payload.
 * Si los datos son insuficientes o están malformados, devuelve `null`.
 *
 * @property fastStatus Estado del bolo rápido (ver [com.ypsopump.sdk.internal.protocol.BolusNotificationStatus]).
 * @property fastStatusName Nombre legible del estado del bolo rápido.
 * @property fastSequence Número de secuencia del bolo rápido.
 * @property slowStatus Estado del bolo extendido (lento).
 * @property slowStatusName Nombre legible del estado del bolo extendido.
 * @property slowSequence Número de secuencia del bolo extendido.
 */
internal data class BolusNotification(
    val fastStatus: Int,
    val fastStatusName: String,
    val fastSequence: Long,
    val slowStatus: Int,
    val slowStatusName: String,
    val slowSequence: Long
) {
    companion object {
        fun parse(data: ByteArray): BolusNotification? {
            if (data.size < 10) return null
            // Strip CRC if present (12+ bytes = 10B payload + 2B CRC)
            val payload = if (data.size >= 12 && YpsoCrc.isValid(data)) {
                data.copyOfRange(0, data.size - 2)
            } else {
                data
            }
            if (payload.size < 10) return null
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val fastStatus = buf.get().toInt() and 0xFF
            val fastSeq = buf.int.toLong() and 0xFFFFFFFFL
            val slowStatus = buf.get().toInt() and 0xFF
            val slowSeq = buf.int.toLong() and 0xFFFFFFFFL
            return BolusNotification(
                fastStatus = fastStatus,
                fastStatusName = BolusNotificationStatus.name(fastStatus),
                fastSequence = fastSeq,
                slowStatus = slowStatus,
                slowStatusName = BolusNotificationStatus.name(slowStatus),
                slowSequence = slowSeq
            )
        }
    }
}
