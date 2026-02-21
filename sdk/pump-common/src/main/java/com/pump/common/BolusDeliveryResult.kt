package com.pump.common

/**
 * Resultado de la ejecución de un comando de bolo enviado a la bomba de insulina.
 *
 * Esta clase encapsula el resultado final de la operación de bolo, incluyendo si fue exitosa,
 * el identificador asignado por la bomba, las unidades solicitadas y las efectivamente
 * administradas, o el motivo del error en caso de fallo.
 *
 * @property success Indica si el bolo fue aceptado y administrado correctamente por la bomba.
 * @property bolusId Identificador único del bolo asignado por la bomba, o null si no está disponible
 *   (p. ej., en caso de error antes de que la bomba confirmase la operación).
 * @property errorMessage Descripción del error ocurrido en caso de fallo, o null si la operación
 *   fue exitosa.
 * @property deliveredUnits Cantidad de insulina efectivamente administrada por la bomba en unidades,
 *   o null si no está disponible (p. ej., si la bomba no reportó el valor final).
 * @property requestedUnits Cantidad de insulina originalmente solicitada en unidades,
 *   o null si no está disponible.
 */
data class BolusDeliveryResult(
    val success: Boolean,
    val bolusId: Int? = null,
    val errorMessage: String? = null,
    val deliveredUnits: Float? = null,
    val requestedUnits: Float? = null
)
