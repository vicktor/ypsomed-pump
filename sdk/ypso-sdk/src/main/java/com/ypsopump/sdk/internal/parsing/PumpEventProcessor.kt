package com.ypsopump.sdk.internal.parsing

import android.util.Log
import com.pump.common.PumpEvent
import com.ypsopump.sdk.internal.data.BolusNotification
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.DeliveryMode
import com.ypsopump.sdk.internal.protocol.EventType

/**
 * Procesa los datos recibidos de la bomba y genera [PumpEvent]s para notificaciones y la UI.
 *
 * Actúa como una máquina de estados que mantiene el último valor conocido de batería,
 * reservorio, modo de entrega y estado de bolo. Solo emite eventos cuando el estado
 * cambia (transiciones), evitando eventos duplicados en cada ciclo de sondeo.
 *
 * ## Fuentes de datos procesadas
 * - **Notificaciones de bolo** ([processBolusNotification]): cambios de estado en la
 *   característica [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION].
 * - **Estado del sistema** ([processSystemStatus]): respuestas periódicas de sondeo
 *   con nivel de batería, insulina restante y modo de entrega.
 * - **Entradas de historial** ([processHistoryEntries]): eventos del historial leídos
 *   desde las características de historial de la bomba.
 *
 * ## Limitación de notificaciones de bolo
 * [processBolusNotification] solo emite [PumpEvent.BolusStarted] (con 0 unidades),
 * ya que no conoce la cantidad solicitada. Los eventos de finalización/error de bolo
 * con la cantidad real los emite la lógica de `startBolus()` directamente.
 *
 * Llamar a [reset] reinicia todos los estados internos, útil al reconectar a la bomba.
 */
internal class PumpEventProcessor {

    companion object {
        private const val TAG = "PumpEventProcessor"
        private const val BATTERY_LOW_THRESHOLD = 20
        private const val BATTERY_EMPTY_THRESHOLD = 5
        private const val RESERVOIR_LOW_THRESHOLD = 20f
        private const val RESERVOIR_EMPTY_THRESHOLD = 5f
    }

    private var lastBatteryPercent: Int = -1
    private var lastReservoirUnits: Float = -1f
    private var lastDeliveryMode: Int = -1
    private var lastFastBolusStatus: Int = BolusNotificationStatus.IDLE
    private var lastSlowBolusStatus: Int = BolusNotificationStatus.IDLE

    var lastKnownEventsCount: Int = -1
    var lastKnownAlertsCount: Int = -1

    /**
     * Procesa los bytes de una notificación de bolo recibida por BLE y emite eventos
     * de transición de estado.
     *
     * Solo genera [PumpEvent.BolusStarted] cuando el estado de bolo rápido o lento
     * transiciona a [BolusNotificationStatus.DELIVERING]. Los eventos de finalización
     * y error se gestionan en otro lugar (ver documentación de la clase).
     *
     * @param data Bytes raw de la notificación (13B con CRC o 10B sin CRC).
     * @return Lista de eventos generados (puede estar vacía si no hay cambios de estado).
     */
    fun processBolusNotification(data: ByteArray): List<PumpEvent> {
        val events = mutableListOf<PumpEvent>()
        val notif = BolusNotification.parse(data) ?: return events

        // Only emit BolusStarted here — completion/error events are emitted by
        // startBolus() with actual units (this processor doesn't know the requested amount)
        if (notif.fastStatus != lastFastBolusStatus) {
            if (notif.fastStatus == BolusNotificationStatus.DELIVERING) {
                events.add(PumpEvent.BolusStarted(0f, "Fast"))
            }
            lastFastBolusStatus = notif.fastStatus
        }

        if (notif.slowStatus != lastSlowBolusStatus) {
            if (notif.slowStatus == BolusNotificationStatus.DELIVERING) {
                events.add(PumpEvent.BolusStarted(0f, "Extended"))
            }
            lastSlowBolusStatus = notif.slowStatus
        }

        return events
    }

    /**
     * Procesa un objeto [SystemStatusData] descifrado y emite eventos de transición.
     *
     * Detecta y emite eventos para los siguientes cambios de estado:
     * - **Batería baja** ([PumpEvent.BatteryLow]): cuando el nivel baja del umbral del 20%.
     * - **Batería vacía** ([PumpEvent.BatteryEmpty]): cuando el nivel baja del umbral del 5%.
     * - **Reservorio bajo** ([PumpEvent.ReservoirLow]): cuando la insulina baja de 20 unidades.
     * - **Reservorio vacío** ([PumpEvent.ReservoirEmpty]): cuando baja de 5 unidades.
     * - **Cartucho cambiado** ([PumpEvent.CartridgeChanged]): si la insulina sube > 50 unidades
     *   respecto al último valor conocido.
     * - **Cambio de modo de entrega** ([PumpEvent.DeliveryModeChanged]): cualquier cambio en
     *   [SystemStatusData.deliveryMode].
     * - **Entrega detenida** ([PumpEvent.DeliveryStopped]): cuando el modo pasa a [DeliveryMode.STOPPED].
     * - **TBR iniciada** ([PumpEvent.TbrStarted]) / **TBR completada** ([PumpEvent.TbrCompleted]):
     *   al entrar/salir del modo [DeliveryMode.TBR].
     *
     * @param status Datos de estado del sistema recién descifrados.
     * @return Lista de eventos generados (puede estar vacía si no hay cambios).
     */
    fun processSystemStatus(status: SystemStatusData): List<PumpEvent> {
        val events = mutableListOf<PumpEvent>()

        if (lastBatteryPercent >= 0) {
            if (status.batteryPercent <= BATTERY_EMPTY_THRESHOLD && lastBatteryPercent > BATTERY_EMPTY_THRESHOLD) {
                events.add(PumpEvent.BatteryEmpty(status.batteryPercent))
            } else if (status.batteryPercent <= BATTERY_LOW_THRESHOLD && lastBatteryPercent > BATTERY_LOW_THRESHOLD) {
                events.add(PumpEvent.BatteryLow(status.batteryPercent))
            }
        }
        lastBatteryPercent = status.batteryPercent

        if (lastReservoirUnits >= 0) {
            if (status.insulinRemaining <= RESERVOIR_EMPTY_THRESHOLD && lastReservoirUnits > RESERVOIR_EMPTY_THRESHOLD) {
                events.add(PumpEvent.ReservoirEmpty(status.insulinRemaining))
            } else if (status.insulinRemaining <= RESERVOIR_LOW_THRESHOLD && lastReservoirUnits > RESERVOIR_LOW_THRESHOLD) {
                events.add(PumpEvent.ReservoirLow(status.insulinRemaining))
            }

            if (status.insulinRemaining > lastReservoirUnits + 50f) {
                events.add(PumpEvent.CartridgeChanged(status.insulinRemaining))
            }
        }
        lastReservoirUnits = status.insulinRemaining

        if (lastDeliveryMode >= 0 && status.deliveryMode != lastDeliveryMode) {
            val oldName = DeliveryMode.name(lastDeliveryMode)
            val newName = DeliveryMode.name(status.deliveryMode)
            events.add(PumpEvent.DeliveryModeChanged(oldName, newName))

            when (status.deliveryMode) {
                DeliveryMode.STOPPED -> events.add(PumpEvent.DeliveryStopped("Pump stopped"))
                DeliveryMode.TBR -> {
                    if (lastDeliveryMode != DeliveryMode.TBR) {
                        events.add(PumpEvent.TbrStarted(0, 0))
                    }
                }
            }

            if (lastDeliveryMode == DeliveryMode.TBR && status.deliveryMode == DeliveryMode.BASAL) {
                events.add(PumpEvent.TbrCompleted(100))
            }
        }
        lastDeliveryMode = status.deliveryMode

        if (events.isNotEmpty()) {
            Log.d(TAG, "Status produced ${events.size} events: ${events.map { it::class.simpleName }}")
        }
        return events
    }

    /**
     * Convierte una lista de entradas del historial de la bomba en eventos [PumpEvent].
     *
     * Mapea cada tipo de entrada de historial ([HistoryEntry.entryType]) al evento
     * correspondiente. Los IDs de evento coinciden con la referencia Python
     * (`Ypso-main/pump/constants.py`). Los valores `value1` y `value2` de las entradas
     * de bolo se dividen entre 100 para convertir de centiunidades a unidades (U).
     *
     * Los tipos de evento no reconocidos se ignoran silenciosamente (devuelven `null`).
     *
     * @param entries Lista de entradas del historial a procesar.
     * @return Lista de [PumpEvent]s generados, en el mismo orden que las entradas de entrada.
     */
    fun processHistoryEntries(entries: List<HistoryEntry>): List<PumpEvent> {
        val events = mutableListOf<PumpEvent>()

        for (entry in entries) {
            // Event IDs match Python reference (Ypso-main/pump/constants.py)
            val event = when (entry.entryType) {
                // Fast bolus events
                EventType.BOLUS_IMMEDIATE_RUNNING ->       // 19: fast bolus in progress
                    PumpEvent.BolusStarted(entry.value1 / 100f, "Fast")
                EventType.BOLUS_IMMEDIATE ->               // 2: fast bolus completed
                    PumpEvent.BolusCompleted(entry.value1 / 100f, entry.value2 / 100f)
                EventType.BOLUS_IMMEDIATE_ABORT ->         // 29: fast bolus aborted
                    PumpEvent.BolusCancelled(entry.value1 / 100f, entry.value2 / 100f)

                // Extended bolus events
                EventType.BOLUS_DELAYED_RUNNING ->         // 1: extended bolus in progress
                    PumpEvent.BolusStarted(entry.value1 / 100f, "Extended")
                EventType.BOLUS_DELAYED ->                 // 3: extended bolus completed
                    PumpEvent.BolusCompleted(entry.value1 / 100f, entry.value2 / 100f)
                EventType.BOLUS_DELAYED_ABORT ->           // 30: extended bolus aborted
                    PumpEvent.BolusCancelled(entry.value1 / 100f, entry.value2 / 100f)

                // Combined bolus events
                EventType.BOLUS_COMBINED_RUNNING ->        // 17: combined bolus in progress
                    PumpEvent.BolusStarted(entry.value1 / 100f, "Combined")
                EventType.BOLUS_COMBINED ->                // 18: combined bolus completed
                    PumpEvent.BolusCompleted(entry.value1 / 100f, entry.value2 / 100f)
                EventType.BOLUS_COMBINED_ABORT ->          // 31: combined bolus aborted
                    PumpEvent.BolusCancelled(entry.value1 / 100f, entry.value2 / 100f)

                // Blind bolus events
                EventType.BOLUS_BLIND_RUNNING ->           // 27: blind bolus in progress
                    PumpEvent.BolusStarted(entry.value1 / 100f, "Blind")
                EventType.BOLUS_BLIND ->                   // 26: blind bolus completed
                    PumpEvent.BolusCompleted(entry.value1 / 100f, entry.value2 / 100f)
                EventType.BOLUS_BLIND_ABORT ->             // 28: blind bolus aborted
                    PumpEvent.BolusCancelled(entry.value1 / 100f, entry.value2 / 100f)

                // Cap change treated as bolus-related info
                EventType.BOLUS_AMOUNT_CAP_CHANGED ->      // 33: bolus cap changed
                    PumpEvent.BolusError("Bolus amount cap changed")

                // TBR events
                EventType.BASAL_PROFILE_TEMP_RUNNING ->    // 9: TBR in progress
                    PumpEvent.TbrStarted(entry.value1, entry.value2)
                EventType.BASAL_PROFILE_TEMP ->            // 10: TBR completed
                    PumpEvent.TbrCompleted(entry.value1)
                EventType.BASAL_PROFILE_TEMP_ABORT ->      // 32: TBR aborted
                    PumpEvent.TbrCancelled(entry.value1)

                // Alert events
                EventType.A_CARTRIDGE_EMPTY ->             // 104
                    PumpEvent.ReservoirEmpty(0f)
                EventType.A_BATTERY_EMPTY ->               // 101
                    PumpEvent.BatteryEmpty(0)
                EventType.A_BATTERY_REMOVED ->             // 100
                    PumpEvent.BatteryEmpty(0)
                EventType.A_OCCLUSION ->                   // 105
                    PumpEvent.Occlusion("Occlusion detected")
                EventType.A_AUTO_STOP ->                   // 106
                    PumpEvent.DeliveryStopped("Auto stop")
                EventType.A_LIPO_DISCHARGED ->             // 107
                    PumpEvent.BatteryEmpty(0)
                EventType.A_REUSABLE_ERROR ->              // 102
                    PumpEvent.DeliveryStopped("Reusable error")
                EventType.A_NO_CARTRIDGE ->                // 103
                    PumpEvent.ReservoirEmpty(0f)
                EventType.A_BATTERY_REJECTED ->            // 108
                    PumpEvent.DeliveryStopped("Battery rejected")

                else -> null
            }
            if (event != null) {
                events.add(event)
                Log.d(TAG, "History entry ${entry.entryTypeName} -> ${event::class.simpleName}")
            }
        }
        return events
    }

    /**
     * Reinicia todos los estados internos del procesador.
     *
     * Debe llamarse al reconectar a la bomba para evitar emitir eventos de transición
     * espurios basados en valores de estado de una sesión anterior. Tras el reset,
     * el primer ciclo de sondeo establece la línea de base sin emitir eventos.
     */
    fun reset() {
        lastBatteryPercent = -1
        lastReservoirUnits = -1f
        lastDeliveryMode = -1
        lastFastBolusStatus = BolusNotificationStatus.IDLE
        lastSlowBolusStatus = BolusNotificationStatus.IDLE
        lastKnownEventsCount = -1
        lastKnownAlertsCount = -1
    }
}
