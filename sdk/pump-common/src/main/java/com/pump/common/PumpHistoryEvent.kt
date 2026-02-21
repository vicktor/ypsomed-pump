package com.pump.common

/**
 * Representa un evento histórico leído de la memoria de la bomba de insulina.
 *
 * Esta clase sellada modela los distintos tipos de registros almacenados en el historial de la
 * bomba: bolos administrados, cambios en la tasa basal temporal, modificaciones de la tasa basal,
 * cambios de cartucho, alertas y eventos desconocidos.
 *
 * @property timestamp Marca de tiempo (epoch en milisegundos) del momento en que ocurrió el evento.
 * @property position Posición del evento en la memoria de la bomba, usada para paginación y
 *   deduplicación al leer el historial de forma incremental.
 */
sealed class PumpHistoryEvent(val timestamp: Long, val position: Long) {

    /**
     * Registro de un bolo de insulina que ha sido administrado completamente.
     *
     * @property amount Cantidad de insulina administrada, en unidades.
     * @property bolusType Tipo de bolo administrado.
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class BolusDelivered(
        val amount: Double,
        val bolusType: BolusType,
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)

    /**
     * Registro del inicio de una tasa basal temporal (TBR).
     *
     * @property percentage Porcentaje de la TBR respecto a la basal programada.
     * @property durationMinutes Duración total de la TBR en minutos.
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class TbrStarted(
        val percentage: Int,
        val durationMinutes: Int,
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)

    /**
     * Registro de la finalización de una tasa basal temporal (TBR), ya sea por expiración
     * natural o por cancelación manual.
     *
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class TbrEnded(
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)

    /**
     * Registro de un cambio en la tasa basal programada activa.
     *
     * @property newRate Nueva tasa basal activa en unidades por hora (U/h).
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class BasalRateChanged(
        val newRate: Double,
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)

    /**
     * Registro de un cambio de cartucho de insulina en la bomba.
     *
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class CartridgeChanged(
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)

    /**
     * Registro de una alerta o alarma generada por la bomba.
     *
     * @property code Código numérico de la alerta tal como lo reporta la bomba.
     * @property message Descripción legible de la alerta.
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class Alert(
        val code: Int,
        val message: String,
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)

    /**
     * Registro de un evento de tipo desconocido que no ha podido ser interpretado.
     *
     * Se utiliza como contenedor genérico para preservar datos no reconocidos del historial,
     * lo que permite el procesamiento tolerante a fallos ante versiones de firmware futuras.
     *
     * @property eventType Identificador numérico del tipo de evento tal como aparece en la memoria de la bomba.
     * @property rawData Bytes sin procesar del registro del evento.
     * @property ts Marca de tiempo (epoch en milisegundos) del evento.
     * @property pos Posición del evento en la memoria de la bomba.
     */
    data class Unknown(
        val eventType: Int,
        val rawData: ByteArray,
        val ts: Long,
        val pos: Long
    ) : PumpHistoryEvent(ts, pos)
}
