package com.pump.common

/**
 * Representa los posibles estados de la conexión BLE con la bomba de insulina.
 *
 * Esta clase sellada modela el ciclo de vida completo de la conexión, desde el emparejamiento
 * inicial hasta la disponibilidad operativa, incluyendo los estados de error y recuperación.
 */
sealed class PumpConnectionState {
    /**
     * La bomba no ha sido emparejada todavía con este dispositivo.
     * Es necesario realizar el proceso de emparejamiento antes de conectar.
     */
    data object NotPaired : PumpConnectionState()

    /**
     * La bomba está desconectada. No existe una sesión BLE activa.
     */
    data object Disconnected : PumpConnectionState()

    /**
     * El controlador está escaneando activamente mediante BLE en busca de la bomba.
     */
    data object Scanning : PumpConnectionState()

    /**
     * Se ha encontrado la bomba y se está estableciendo la conexión BLE.
     */
    data object Connecting : PumpConnectionState()

    /**
     * La conexión BLE está establecida y se está realizando la inicialización del protocolo
     * (p. ej., descubrimiento de servicios, sincronización de contadores, lectura de estado).
     */
    data object Initializing : PumpConnectionState()

    /**
     * El proceso de conexión requiere confirmación explícita por parte del usuario.
     *
     * @property message Mensaje descriptivo que se debe mostrar al usuario.
     * @property code Código de verificación opcional que el usuario debe confirmar en la bomba.
     */
    data class AwaitingUserConfirmation(val message: String, val code: String? = null) : PumpConnectionState()

    /**
     * La bomba está conectada, inicializada y lista para recibir comandos.
     */
    data object Ready : PumpConnectionState()

    /**
     * Se ha perdido la conexión y el controlador está intentando reconectarse automáticamente.
     *
     * @property attempt Número del intento de reconexión actual (comenzando en 1).
     */
    data class Recovering(val attempt: Int) : PumpConnectionState()

    /**
     * Se ha producido un error que ha impedido establecer o mantener la conexión.
     *
     * @property message Descripción legible del error ocurrido.
     * @property exception Excepción subyacente asociada al error, si está disponible.
     */
    data class Error(val message: String, val exception: Throwable? = null) : PumpConnectionState()
}
