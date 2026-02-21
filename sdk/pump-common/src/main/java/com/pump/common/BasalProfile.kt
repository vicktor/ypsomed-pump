package com.pump.common

/**
 * Representa un perfil de insulina basal programado en la bomba.
 *
 * Un perfil basal define la cantidad de insulina de fondo que la bomba administra a lo largo
 * del día, dividida en bloques horarios consecutivos cuya duración total suma 24 horas (1440 minutos).
 *
 * @property profileId Identificador numérico del perfil asignado por la bomba.
 * @property name Nombre descriptivo del perfil (p. ej., "Perfil A", "Día laborable").
 * @property blocks Lista ordenada de bloques horarios que componen el perfil basal.
 *   Los bloques se aplican de forma consecutiva comenzando desde medianoche (00:00).
 */
data class BasalProfile(
    val profileId: Int,
    val name: String,
    val blocks: List<BasalBlock>
)

/**
 * Representa un bloque horario dentro de un perfil basal.
 *
 * Cada bloque define una tasa de administración de insulina constante durante un período de
 * tiempo determinado. Los bloques consecutivos de un perfil cubren las 24 horas del día.
 *
 * @property durationMinutes Duración del bloque en minutos.
 * @property basalRate Tasa basal para este bloque, en unidades por hora (U/h).
 */
data class BasalBlock(
    val durationMinutes: Int,
    val basalRate: Double
)
