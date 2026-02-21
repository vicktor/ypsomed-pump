package com.ypsopump.sdk.internal.ble

import java.util.UUID

/**
 * Repositorio centralizado de todos los UUIDs BLE utilizados para comunicarse con la bomba YpsoPump.
 *
 * Incluye los UUIDs de:
 * - **Servicios BLE**: seguridad y escritura de comandos.
 * - **Autenticación**: característica de contraseña.
 * - **Versiones**: firmware, base, configuración e historial.
 * - **Hora del sistema**: fecha y hora.
 * - **Control de infusión**: inicio/parada de bolo, TBR, estado del sistema y notificaciones de bolo.
 * - **Lectura extendida**: característica de lectura multi-trama.
 * - **Configuración**: índice y valor de ajustes (SETTING_ID / SETTING_VALUE).
 * - **Historial**: entradas de eventos, alertas y registros del sistema (count/index/value).
 * - **Transporte de comandos**: canales de lectura/escritura del servicio de escritura.
 * - **Información del dispositivo**: características BLE estándar (fabricante, modelo, serie, firmware…).
 * - **Notificaciones activas**: conjunto de características que requieren suscripción CCCD.
 *
 * Todos los UUIDs están verificados contra la aplicación mylife y el firmware V05.02.03.
 */
internal object YpsoPumpUuids {

    // ==================== YPSOPUMP SERVICES ====================

    val SERVICE_SECURITY: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
    val SERVICE_WRITE: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")

    // ==================== AUTHENTICATION ====================

    val CHAR_AUTH_PASSWORD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")

    // ==================== VERSION CHARACTERISTICS ====================

    val CHAR_MASTER_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb0147bc5")
    val CHAR_BASE_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee23b7bc5")
    val CHAR_SETTINGS_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee33b7bc5")
    val CHAR_HISTORY_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee43b7bc5")

    // ==================== TIME ====================

    val CHAR_SYSTEM_DATE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedc3b7bc5")
    val CHAR_SYSTEM_TIME: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedd3b7bc5")

    // ==================== CONTROL SERVICE ====================

    val CHAR_BOLUS_START_STOP: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee18b7bc5")
    val CHAR_BOLUS_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
    val CHAR_TBR_START_STOP: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee38b7bc5")
    val CHAR_SYSTEM_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
    val CHAR_BOLUS_NOTIFICATION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee58b7bc5")
    val CHAR_SEC_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee08b7bc5")

    // ==================== EXTENDED READ ====================

    val CHAR_EXTENDED_READ: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")

    // ==================== SETTINGS ====================

    val CHAR_SETTING_ID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb3147bc5")
    val CHAR_SETTING_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb4147bc5")

    // ==================== HISTORY ENTRIES ====================

    object Events {
        val COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        val INDEX: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecc3b7bc5")
        val VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
    }

    object Alerts {
        val COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbec83b7bc5")
        val INDEX: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbec93b7bc5")
        val VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeca3b7bc5")
    }

    object System {
        val COUNT: UUID = UUID.fromString("86a5a431-d442-2c8d-304b-19ee355571fc")
        val INDEX: UUID = UUID.fromString("381ddce9-e934-b4ae-e345-eb87283db426")
        val VALUE: UUID = UUID.fromString("ae3022af-2ec8-bf88-e64c-da68c9a3891a")
    }

    // ==================== WRITE SERVICE (command transport) ====================

    val CHAR_CMD_READ_A: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000a")
    val CHAR_CMD_WRITE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000b")
    val CHAR_CMD_READ_C: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000c")

    // ==================== STANDARD BLE DEVICE INFO ====================

    val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val CHAR_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val CHAR_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val CHAR_FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CHAR_SOFTWARE: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val CHAR_DEVICE_NAME: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ==================== NOTIFICATION SET ====================

    val NOTIFICATION_CHARACTERISTICS = setOf(
        CHAR_BOLUS_STATUS,
        CHAR_BOLUS_NOTIFICATION
    )
}
