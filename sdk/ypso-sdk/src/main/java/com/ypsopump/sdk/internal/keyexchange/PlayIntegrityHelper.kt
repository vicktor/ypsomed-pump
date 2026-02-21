package com.ypsopump.sdk.internal.keyexchange

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Helper para obtener el token de Google Play Integrity requerido por el servidor Proregia
 * durante el intercambio de claves con la bomba YpsoPump.
 *
 * Google Play Integrity permite a Proregia verificar que la solicitud proviene de una
 * aplicación auténtica instalada desde Google Play, no de un dispositivo rooteado o
 * de una aplicación modificada. Sin un token válido, Proregia rechaza la petición
 * de `EncryptKey/Send`.
 *
 * La función [requestToken] es una coroutine suspendida que envuelve la API asíncrona
 * de `IntegrityManager` usando `suspendCoroutine`. Se debe llamar desde un contexto
 * de coroutine (p. ej. `lifecycleScope`, `viewModelScope`).
 *
 * ## Requisitos
 * - Dispositivo con Google Play Services instalado.
 * - El número de proyecto de Google Cloud (`cloudProjectNumber`) debe corresponder
 *   al proyecto configurado en la Google Play Console de la aplicación.
 */
internal object PlayIntegrityHelper {

    private const val TAG = "PlayIntegrityHelper"

    /**
     * Solicita un token de Google Play Integrity de forma suspendida.
     *
     * El nonce se incluye en el token firmado por Google para vincularlo a esta
     * solicitud específica. Proregia verifica el nonce en el token para prevenir
     * ataques de repetición.
     *
     * @param context Contexto Android para crear el [com.google.android.play.core.integrity.IntegrityManager].
     * @param nonce Cadena nonce que se incluirá en el token de integridad. Debe ser una
     *        cadena Base64 o hexadecimal que represente el nonce de servidor de Proregia.
     * @param cloudProjectNumber Número del proyecto de Google Cloud vinculado a la app.
     * @return Token de Play Integrity como cadena JWT, listo para enviarse a Proregia.
     * @throws Exception Si Play Integrity no está disponible o la solicitud falla.
     */
    suspend fun requestToken(
        context: Context,
        nonce: String,
        cloudProjectNumber: Long
    ): String = suspendCoroutine { continuation ->
        Log.d(TAG, "Requesting Play Integrity token...")
        Log.d(TAG, "Cloud project: $cloudProjectNumber")
        Log.d(TAG, "Nonce: $nonce")

        val integrityManager = IntegrityManagerFactory.create(context)

        val request = IntegrityTokenRequest.builder()
            .setCloudProjectNumber(cloudProjectNumber)
            .setNonce(nonce)
            .build()

        integrityManager.requestIntegrityToken(request)
            .addOnSuccessListener { response ->
                val token = response.token()
                Log.d(TAG, "Got Play Integrity token (${token.length} chars)")
                Log.d(TAG, "Token preview: ${token.take(50)}...")
                continuation.resume(token)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Play Integrity request failed", exception)
                continuation.resumeWithException(exception)
            }
    }
}
