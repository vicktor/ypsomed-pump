package com.pump.common

/**
 * Representa el estado actual de la bomba de insulina.
 *
 * Esta clase encapsula toda la información de estado relevante de la bomba: identificación del
 * dispositivo, batería, reservorio, modo de operación, tasa basal activa, TBR activo, bolo activo,
 * alarmas y advertencias.
 *
 * @property serialNumber Número de serie del dispositivo.
 * @property firmwareVersion Versión del firmware instalado en la bomba.
 * @property manufacturer Nombre del fabricante del dispositivo.
 * @property batteryPercent Nivel de batería en porcentaje (0–100), o null si no está disponible.
 * @property reservoirUnits Unidades de insulina restantes en el reservorio, o null si no están disponibles.
 * @property isDelivering Indica si la bomba está administrando insulina activamente.
 * @property operatingMode Modo de operación actual de la bomba.
 * @property activeBasalRate Tasa basal activa en unidades por hora (U/h), o null si no está disponible.
 * @property activeProfileId Identificador del perfil basal activo, o null si no está disponible.
 * @property activeTbr Información sobre la tasa basal temporal (TBR) activa, o null si no hay ninguna.
 * @property activeBolus Información sobre el bolo activo en curso, o null si no hay ninguno.
 * @property alarmCode Código de la alarma activa. El valor 0 indica que no hay ninguna alarma.
 * @property warningCode Código de la advertencia activa. El valor 0 indica que no hay ninguna advertencia.
 * @property deliveryModeName Nombre legible del modo de administración actual.
 * @property lastBolusUnits Cantidad de insulina del último bolo administrado, en unidades.
 * @property lastBolusTime Marca de tiempo (epoch en milisegundos) del último bolo administrado.
 */
data class PumpStatus(
    val serialNumber: String = "",
    val firmwareVersion: String = "",
    val manufacturer: String = "",
    val batteryPercent: Int? = null,
    val reservoirUnits: Double? = null,
    val isDelivering: Boolean = false,
    val operatingMode: OperatingMode = OperatingMode.UNKNOWN,
    val activeBasalRate: Double? = null,
    val activeProfileId: Int? = null,
    val activeTbr: ActiveTbr? = null,
    val activeBolus: ActiveBolus? = null,
    val alarmCode: Int = 0,
    val warningCode: Int = 0,
    val deliveryModeName: String = "",
    val lastBolusUnits: Double = 0.0,
    val lastBolusTime: Long = 0
) {
    /**
     * Devuelve true si hay una tasa basal temporal (TBR) activa con tiempo restante mayor que cero.
     */
    val hasTbrActive: Boolean
        get() = activeTbr != null && (activeTbr.remainingMinutes > 0)

    /**
     * Devuelve true si hay una alarma activa (código de alarma distinto de cero).
     */
    val hasActiveAlarm: Boolean
        get() = alarmCode != 0

    /**
     * Devuelve true si hay una advertencia activa (código de advertencia distinto de cero).
     */
    val hasWarning: Boolean
        get() = warningCode != 0
}

/**
 * Modo de operación de la bomba de insulina.
 *
 * - [STARTED]: La bomba está en funcionamiento y administrando insulina.
 * - [STOPPED]: La bomba está detenida y no administra insulina.
 * - [PAUSED]: La bomba está en pausa temporal.
 * - [UNKNOWN]: El modo de operación no se ha podido determinar.
 */
enum class OperatingMode { STARTED, STOPPED, PAUSED, UNKNOWN }

/**
 * Representa una tasa basal temporal (TBR) activa en la bomba.
 *
 * @property percentage Porcentaje de la tasa basal respecto a la basal programada (p. ej., 50 = 50%).
 * @property remainingMinutes Minutos restantes hasta que finalice la TBR.
 * @property initialDurationMinutes Duración total inicial de la TBR en minutos.
 */
data class ActiveTbr(
    val percentage: Int,
    val remainingMinutes: Int,
    val initialDurationMinutes: Int = 0
)

/**
 * Representa un bolo de insulina actualmente en curso.
 *
 * @property bolusId Identificador único del bolo asignado por la bomba, o null si no está disponible.
 * @property bolusType Tipo de bolo (estándar, extendido o multionda).
 * @property requestedAmount Cantidad de insulina solicitada, en unidades.
 * @property deliveredAmount Cantidad de insulina ya administrada, en unidades.
 * @property remainingMinutes Minutos restantes para completar el bolo (relevante para bolo extendido), o null si no aplica.
 */
data class ActiveBolus(
    val bolusId: Int? = null,
    val bolusType: BolusType = BolusType.STANDARD,
    val requestedAmount: Double = 0.0,
    val deliveredAmount: Double = 0.0,
    val remainingMinutes: Int? = null
)

/**
 * Tipo de bolo de insulina.
 *
 * - [STANDARD]: Bolo normal administrado de forma inmediata.
 * - [EXTENDED]: Bolo administrado de forma gradual a lo largo de un período de tiempo.
 * - [MULTIWAVE]: Combinación de bolo inmediato y extendido.
 */
enum class BolusType { STANDARD, EXTENDED, MULTIWAVE }
