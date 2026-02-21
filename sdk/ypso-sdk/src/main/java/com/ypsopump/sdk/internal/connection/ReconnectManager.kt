package com.ypsopump.sdk.internal.connection

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gestiona la reconexión automática a la bomba YpsoPump con retroceso exponencial.
 *
 * Cuando se detecta una desconexión BLE ([onDisconnect]), el manager programa un intento
 * de reconexión tras un delay inicial de 5 segundos. Cada intento fallido duplica el
 * tiempo de espera hasta un máximo de 300 segundos (5 minutos).
 *
 * La secuencia de delays es: 5s → 10s → 20s → 40s → 80s → 160s → 300s → 300s → ...
 *
 * Al conectarse con éxito ([onConnected]), los contadores y el delay se reinician al
 * valor inicial. Se puede activar/desactivar la reconexión automática con [enable]/[disable].
 *
 * @param scope [CoroutineScope] en el que se lanza el job de reconexión.
 * @param onReconnect Callback invocado cuando se debe ejecutar el intento de reconexión.
 * @param onStatusUpdate Callback invocado con mensajes de estado para la UI.
 */
internal class ReconnectManager(
    private val scope: CoroutineScope,
    private val onReconnect: () -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "ReconnectManager"
        private const val INITIAL_DELAY_MS = 5_000L
        private const val MAX_DELAY_MS = 300_000L
        private const val BACKOFF_FACTOR = 2.0
    }

    private var currentDelayMs = INITIAL_DELAY_MS
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var enabled = false

    /**
     * Debe llamarse cuando se detecta una desconexión BLE.
     *
     * Si la reconexión automática está habilitada, cancela cualquier job pendiente,
     * incrementa el contador de intentos, notifica el estado a la UI y programa
     * un nuevo intento tras el delay de retroceso actual.
     * Tras ejecutar el intento, el delay se duplica (hasta el máximo de 300 segundos).
     *
     * Si la reconexión está deshabilitada ([isEnabled] == `false`), no hace nada.
     */
    fun onDisconnect() {
        if (!enabled) return
        attempt++
        Log.d(TAG, "Scheduling reconnect attempt $attempt in ${currentDelayMs}ms")
        onStatusUpdate("Reconnecting... (attempt $attempt, ${currentDelayMs / 1000}s)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(currentDelayMs)
            Log.d(TAG, "Executing reconnect attempt $attempt")
            onStatusUpdate("Reconnecting... (attempt $attempt)")
            onReconnect()
            currentDelayMs = (currentDelayMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_DELAY_MS)
        }
    }

    /**
     * Debe llamarse cuando la conexión BLE se establece con éxito.
     *
     * Cancela cualquier job de reconexión pendiente y reinicia el delay de retroceso
     * al valor inicial (5 segundos) y el contador de intentos a 0.
     */
    fun onConnected() {
        Log.d(TAG, "Connected, resetting backoff")
        reconnectJob?.cancel()
        reconnectJob = null
        currentDelayMs = INITIAL_DELAY_MS
        attempt = 0
    }

    /**
     * Habilita la reconexión automática. A partir de este momento, las llamadas
     * a [onDisconnect] programarán intentos de reconexión.
     */
    fun enable() {
        enabled = true
        Log.d(TAG, "Auto-reconnect enabled")
    }

    /**
     * Deshabilita la reconexión automática y cancela cualquier job pendiente.
     * Reinicia el delay y el contador de intentos.
     */
    fun disable() {
        enabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        currentDelayMs = INITIAL_DELAY_MS
        attempt = 0
        Log.d(TAG, "Auto-reconnect disabled")
    }

    val isEnabled: Boolean get() = enabled
    val currentAttempt: Int get() = attempt
}
