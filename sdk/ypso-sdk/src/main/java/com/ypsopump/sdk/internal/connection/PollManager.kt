package com.ypsopump.sdk.internal.connection

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gestiona el ciclo de sondeo periódico de estado de la bomba cada 60 segundos.
 *
 * En cada ciclo de sondeo se adquiere un `PARTIAL_WAKE_LOCK` con un timeout de 30 segundos
 * para evitar que el procesador entre en suspensión durante la operación BLE, garantizando
 * así que el sondeo se completa incluso con la pantalla apagada. El WakeLock se libera
 * automáticamente al finalizar [onPoll], ya sea con éxito o con error.
 *
 * El primer ciclo se retarda 60 segundos desde el inicio para no solaparse con las
 * operaciones iniciales de conexión y sincronización.
 *
 * @param context Contexto Android para acceder al [android.os.PowerManager].
 * @param scope [CoroutineScope] en el que se lanza el bucle de sondeo.
 * @param onPoll Función suspendida que se ejecuta en cada ciclo de sondeo.
 *        Típicamente realiza una lectura del estado del sistema de la bomba.
 */
internal class PollManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPoll: suspend () -> Unit
) {
    companion object {
        private const val TAG = "PollManager"
        private const val POLLING_INTERVAL_MS = 60_000L
        private const val WAKELOCK_TIMEOUT_MS = 30_000L
    }

    private var pollingJob: Job? = null

    /**
     * Inicia el bucle de sondeo periódico.
     *
     * Si ya hay un bucle en marcha, lo detiene antes de iniciar uno nuevo.
     * El primer sondeo se ejecuta tras el primer intervalo de 60 segundos.
     * Los sondeos subsiguientes se ejecutan cada 60 segundos hasta que se llame a [stop].
     */
    fun start() {
        stop()
        Log.d(TAG, "Starting 60s polling loop (per-poll WakeLock)")

        pollingJob = scope.launch {
            delay(POLLING_INTERVAL_MS)
            while (true) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "YpsoPump::PollCycle"
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
                try {
                    onPoll()
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    /**
     * Detiene el bucle de sondeo cancelando el job de coroutine activo.
     *
     * Es seguro llamar a este método aunque el sondeo no esté en marcha.
     */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    val isRunning: Boolean get() = pollingJob?.isActive == true
}
