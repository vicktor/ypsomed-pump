package com.pump.common

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaz principal para controlar una bomba de insulina.
 *
 * Define el contrato genérico que cualquier implementación de driver de bomba debe cumplir.
 * La app consumidora trabaja exclusivamente con esta interfaz, sin depender de detalles
 * específicos del hardware (BLE, protocolo, cifrado, etc.).
 *
 * Flujo típico de uso:
 * 1. Obtener una instancia de [PumpManager] (p.ej. [com.ypsopump.sdk.YpsoPumpManagerImpl])
 * 2. Observar [connectionState] y [pumpStatus] mediante Flows
 * 3. Llamar a [connect] para establecer la conexión BLE
 * 4. Usar los métodos de bolo, TBR, perfiles basales, etc.
 * 5. Observar [pumpEvents] para recibir notificaciones en tiempo real
 *
 * Todas las operaciones de comando (bolo, TBR, sincronización) son suspendidas y
 * pueden lanzar excepciones si la comunicación falla. Las operaciones críticas
 * (bolos, TBR) incluyen reintentos automáticos en la implementación.
 *
 * @see PumpConnectionState estados posibles de la conexión
 * @see PumpStatus datos de estado de la bomba
 * @see PumpEvent eventos en tiempo real emitidos por la bomba
 */
interface PumpManager {

    /** Estado actual de la conexión BLE con la bomba. */
    val connectionState: StateFlow<PumpConnectionState>

    /** Estado más reciente de la bomba (batería, reservorio, modo de entrega, etc.). `null` si aún no se ha leído. */
    val pumpStatus: StateFlow<PumpStatus?>

    /** Flujo de eventos en tiempo real: bolos, TBR, alertas, batería, reservorio, etc. */
    val pumpEvents: SharedFlow<PumpEvent>

    /** `true` si hay una bomba vinculada (dirección MAC guardada). */
    val isPaired: Boolean

    /** Dirección MAC Bluetooth de la bomba vinculada, o `null` si no hay ninguna. */
    val pairedAddress: String?

    // ==================== Conexión ====================

    /** Establece la conexión BLE con la bomba vinculada. */
    suspend fun connect()

    /** Desconecta de la bomba de forma limpia. */
    suspend fun disconnect()

    /** Confirma una solicitud de vinculación pendiente (ver [PumpConnectionState.AwaitingUserConfirmation]). */
    suspend fun confirmPairing()

    /** Rechaza una solicitud de vinculación pendiente. */
    suspend fun rejectPairing()

    /** Elimina la vinculación: borra la dirección MAC guardada y el material criptográfico. */
    suspend fun unpair()

    // ==================== Estado ====================

    /**
     * Lee el estado completo de la bomba: batería, reservorio, modo de entrega, perfiles, etc.
     * Requiere conexión activa.
     */
    suspend fun getFullStatus(): PumpStatus

    // ==================== Bolos ====================

    /**
     * Administra un bolo estándar (inmediato).
     *
     * @param amount cantidad total en unidades (p.ej. 1.5 = 1.5U). Rango: 0.01–25.0U.
     * @return resultado con éxito/fallo, unidades entregadas y solicitadas
     */
    suspend fun deliverStandardBolus(amount: Double): BolusDeliveryResult

    /**
     * Administra un bolo extendido (entrega gradual durante un periodo).
     *
     * @param amount cantidad total en unidades
     * @param durationMinutes duración de la entrega en minutos (> 0)
     * @return resultado con éxito/fallo, unidades entregadas y solicitadas
     */
    suspend fun deliverExtendedBolus(amount: Double, durationMinutes: Int): BolusDeliveryResult

    /**
     * Administra un bolo multiwave (parte inmediata + parte extendida).
     *
     * @param immediateAmount parte inmediata en unidades
     * @param extendedAmount parte extendida en unidades
     * @param durationMinutes duración de la parte extendida en minutos
     * @return resultado con éxito/fallo, unidades entregadas y solicitadas
     */
    suspend fun deliverMultiwaveBolus(
        immediateAmount: Double,
        extendedAmount: Double,
        durationMinutes: Int
    ): BolusDeliveryResult

    /**
     * Cancela un bolo en curso.
     *
     * @param bolusId identificador del bolo a cancelar (opcional; si es null cancela el activo)
     * @return `true` si la cancelación fue aceptada por la bomba
     */
    suspend fun cancelBolus(bolusId: Int? = null): Boolean

    // ==================== Tasa Basal Temporal (TBR) ====================

    /**
     * Establece una tasa basal temporal.
     *
     * @param percentage porcentaje respecto a la basal programada (p.ej. 50 = 50%, 200 = 200%)
     * @param durationMinutes duración en minutos
     * @return `true` si la bomba aceptó el comando
     */
    suspend fun setTbr(percentage: Int, durationMinutes: Int): Boolean

    /**
     * Cancela la TBR activa, volviendo a la basal programada (equivale a 100%, 0min).
     *
     * @return `true` si la bomba aceptó la cancelación
     */
    suspend fun cancelTbr(): Boolean

    // ==================== Fecha y Hora ====================

    /**
     * Sincroniza la fecha y hora de la bomba con la del dispositivo Android.
     *
     * @return `true` si la sincronización fue exitosa
     */
    suspend fun syncDateTime(): Boolean

    // ==================== Perfiles Basales ====================

    /**
     * Lee un perfil basal de la bomba.
     *
     * @param profileId identificador del perfil (0 = A, 1 = B)
     * @return perfil basal con sus bloques horarios, o `null` si falla la lectura
     */
    suspend fun readBasalProfile(profileId: Int): BasalProfile?

    /**
     * Escribe un perfil basal en la bomba.
     *
     * @param profile perfil con los bloques horarios a programar
     * @return `true` si la escritura fue aceptada
     */
    suspend fun writeBasalProfile(profile: BasalProfile): Boolean

    /**
     * Obtiene el ID del perfil basal activo.
     *
     * @return 0 (A), 1 (B), o `null` si no se puede leer
     */
    suspend fun getActiveProfileId(): Int?

    /**
     * Cambia el perfil basal activo.
     *
     * @param profileId 0 (A) o 1 (B)
     * @return `true` si el cambio fue aceptado
     */
    suspend fun setActiveProfile(profileId: Int): Boolean

    // ==================== Historial ====================

    /**
     * Lee entradas del historial de la bomba.
     *
     * @param sincePosition posición desde la cual leer (-1 = las más recientes)
     * @param maxEvents número máximo de entradas a leer
     * @return lista de eventos históricos ordenados del más reciente al más antiguo
     */
    suspend fun readHistory(sincePosition: Long = -1, maxEvents: Int = 100): List<PumpHistoryEvent>

    // ==================== Configuración ====================

    /**
     * Establece un parámetro de configuración del SDK.
     *
     * @param key clave de configuración (ver [CONFIG_RELAY_URL], [CONFIG_SHARED_KEY])
     * @param value valor a asignar
     */
    fun configure(key: String, value: Any)

    // ==================== Capacidades ====================

    /** Indica si la bomba soporta cambio de modos de operación (inicio/pausa/parada). */
    val supportsOperatingModes: Boolean get() = false

    /** Número máximo de perfiles basales que soporta la bomba (p.ej. 2 para YpsoPump). */
    val maxBasalProfiles: Int

    /** Cantidad máxima de bolo en unidades que acepta la bomba. */
    val maxBolusAmount: Double

    /** Rango de porcentaje de TBR soportado (p.ej. 0..250). */
    val tbrPercentRange: IntRange

    companion object {
        /** Clave de configuración: URL del servidor relay para intercambio de claves. */
        const val CONFIG_RELAY_URL = "relay_url"

        /** Clave de configuración: clave compartida en hex (alternativa al intercambio automático). */
        const val CONFIG_SHARED_KEY = "shared_key"
    }
}
