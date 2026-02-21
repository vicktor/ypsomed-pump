package com.pump.common

/**
 * Representa un evento en tiempo real emitido durante el funcionamiento de la bomba de insulina.
 *
 * Esta clase sellada agrupa todos los eventos que pueden ocurrir durante una sesión activa:
 * cambios en la batería, el reservorio, la administración de bolos, la tasa basal temporal (TBR),
 * el modo de administración y el estado de la conexión BLE.
 *
 * @property timestamp Marca de tiempo (epoch en milisegundos) en la que se generó el evento.
 *   Por defecto corresponde al momento de instanciación.
 */
sealed class PumpEvent(val timestamp: Long = System.currentTimeMillis()) {

    // Battery

    /**
     * La batería de la bomba ha alcanzado un nivel bajo.
     *
     * @property percent Nivel de batería actual en porcentaje.
     */
    data class BatteryLow(val percent: Int) : PumpEvent()

    /**
     * La batería de la bomba está prácticamente agotada y requiere sustitución inmediata.
     *
     * @property percent Nivel de batería actual en porcentaje.
     */
    data class BatteryEmpty(val percent: Int) : PumpEvent()

    // Reservoir

    /**
     * El nivel de insulina en el reservorio ha alcanzado un umbral bajo.
     *
     * @property unitsRemaining Unidades de insulina restantes en el reservorio.
     */
    data class ReservoirLow(val unitsRemaining: Float) : PumpEvent()

    /**
     * El reservorio de insulina está vacío o prácticamente vacío.
     *
     * @property unitsRemaining Unidades de insulina restantes en el reservorio.
     */
    data class ReservoirEmpty(val unitsRemaining: Float) : PumpEvent()

    /**
     * Se ha detectado un cambio de cartucho en la bomba.
     *
     * @property newUnits Unidades de insulina cargadas en el nuevo cartucho.
     */
    data class CartridgeChanged(val newUnits: Float) : PumpEvent()

    // Bolus

    /**
     * Se ha iniciado la administración de un bolo de insulina.
     *
     * @property units Cantidad de insulina solicitada para el bolo, en unidades.
     * @property type Tipo de bolo (p. ej., "STANDARD", "EXTENDED", "MULTIWAVE").
     */
    data class BolusStarted(val units: Float, val type: String) : PumpEvent()

    /**
     * El bolo de insulina se ha completado satisfactoriamente.
     *
     * @property delivered Cantidad de insulina efectivamente administrada, en unidades.
     * @property requested Cantidad de insulina originalmente solicitada, en unidades.
     */
    data class BolusCompleted(val delivered: Float, val requested: Float) : PumpEvent()

    /**
     * El bolo de insulina ha sido cancelado antes de completarse.
     *
     * @property delivered Cantidad de insulina administrada hasta el momento de la cancelación, en unidades.
     * @property requested Cantidad de insulina originalmente solicitada, en unidades.
     */
    data class BolusCancelled(val delivered: Float, val requested: Float) : PumpEvent()

    /**
     * Se ha producido un error durante la administración del bolo.
     *
     * @property message Descripción del error ocurrido.
     */
    data class BolusError(val message: String) : PumpEvent()

    // TBR

    /**
     * Se ha iniciado una tasa basal temporal (TBR).
     *
     * @property percent Porcentaje de la tasa basal temporal respecto a la basal programada.
     * @property durationMin Duración total de la TBR en minutos.
     */
    data class TbrStarted(val percent: Int, val durationMin: Int) : PumpEvent()

    /**
     * La tasa basal temporal (TBR) ha finalizado normalmente al agotarse su duración.
     *
     * @property percent Porcentaje de la TBR que ha concluido.
     */
    data class TbrCompleted(val percent: Int) : PumpEvent()

    /**
     * La tasa basal temporal (TBR) ha sido cancelada antes de completar su duración.
     *
     * @property percent Porcentaje de la TBR que ha sido cancelada.
     */
    data class TbrCancelled(val percent: Int) : PumpEvent()

    // Delivery

    /**
     * El modo de administración de la bomba ha cambiado.
     *
     * @property oldMode Nombre del modo de administración anterior.
     * @property newMode Nombre del nuevo modo de administración activo.
     */
    data class DeliveryModeChanged(val oldMode: String, val newMode: String) : PumpEvent()

    /**
     * Se ha detectado una oclusión en el sistema de infusión.
     *
     * @property message Descripción del evento de oclusión.
     */
    data class Occlusion(val message: String) : PumpEvent()

    /**
     * La administración de insulina se ha detenido.
     *
     * @property reason Motivo por el que se ha detenido la administración.
     */
    data class DeliveryStopped(val reason: String) : PumpEvent()

    // Connection

    /**
     * Se ha perdido la conexión BLE con la bomba.
     *
     * @property reason Motivo por el que se ha perdido la conexión.
     */
    data class ConnectionLost(val reason: String) : PumpEvent()

    /**
     * Se ha restablecido la conexión BLE con la bomba tras una desconexión.
     *
     * @property deviceName Nombre BLE del dispositivo reconectado.
     */
    data class ConnectionRestored(val deviceName: String) : PumpEvent()
}
