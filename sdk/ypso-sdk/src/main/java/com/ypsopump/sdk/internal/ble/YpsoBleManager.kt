package com.ypsopump.sdk.internal.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import com.ypsopump.sdk.internal.crypto.PumpCryptor
import com.ypsopump.sdk.internal.data.BolusStatusData
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.DeliveryMode
import com.ypsopump.sdk.internal.protocol.SettingsIndex
import com.ypsopump.sdk.internal.protocol.YpsoCrc
import com.ypsopump.sdk.internal.protocol.YpsoFraming
import com.ypsopump.sdk.internal.protocol.YpsoGlb
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Capa de comunicación BLE con la bomba YpsoPump, basada en la librería Nordic BLE.
 *
 * Esta clase gestiona todo el ciclo de vida de la conexión Bluetooth: descubrimiento de servicios
 * y características, autenticación mediante contraseña MD5 derivada de la MAC del dispositivo,
 * lecturas y escrituras cifradas con XChaCha20-Poly1305, el protocolo multi-trama (framing)
 * propio de Ypsomed, y la recepción de notificaciones BLE.
 *
 * Todos los comandos a la bomba pasan por aquí: bolo, TBR, sincronización de hora,
 * lectura de perfil basal, historial, y configuración general.
 *
 * El cifrado es opcional: si [pumpCryptor] es null, los datos se envían/reciben en claro.
 * Los contadores de cifrado (reboot_counter, write_counter) se sincronizan automáticamente
 * leyendo el estado del sistema antes de cualquier escritura cifrada.
 */
internal class YpsoBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "YpsoBleManager"
        const val YPSOPUMP_NAME_PREFIX = "YpsoPump_"

        private val AUTH_SALT = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(),
            0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )
    }

    init {
        connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                Log.d(TAG, "Connecting to ${device.name}...")
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.d(TAG, "Connected to ${device.name}")
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.e(TAG, "Failed to connect: reason=$reason")
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                Log.d(TAG, "Device ready: ${device.name}")
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                Log.d(TAG, "Disconnecting from ${device.name}")
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Log.d(TAG, "Disconnected: reason=$reason")
                _isConnected.value = false
                _isAuthenticated.value = false
                onDisconnectCallback?.invoke(reason)
            }
        }
    }

    override fun shouldClearCacheWhenDisconnected(): Boolean = true

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _logEvents = MutableSharedFlow<LogEvent>(replay = 100)
    val logEvents: SharedFlow<LogEvent> = _logEvents

    private val _dataReceived = MutableSharedFlow<DataEvent>(replay = 10)
    val dataReceived: SharedFlow<DataEvent> = _dataReceived

    // Standard BLE characteristics
    private var charDeviceName: BluetoothGattCharacteristic? = null
    private var charManufacturer: BluetoothGattCharacteristic? = null
    private var charSoftwareRevision: BluetoothGattCharacteristic? = null

    // Authentication
    private var charAuthPassword: BluetoothGattCharacteristic? = null

    // Version
    private var charMasterVersion: BluetoothGattCharacteristic? = null

    // Control
    private var charBolusStartStop: BluetoothGattCharacteristic? = null
    private var charBolusStatus: BluetoothGattCharacteristic? = null
    private var charTbrStartStop: BluetoothGattCharacteristic? = null
    private var charSystemStatus: BluetoothGattCharacteristic? = null
    private var charBolusNotification: BluetoothGattCharacteristic? = null

    // Time
    private var charSystemDate: BluetoothGattCharacteristic? = null
    private var charSystemTime: BluetoothGattCharacteristic? = null

    // Extended read
    private var charExtendedRead: BluetoothGattCharacteristic? = null

    // Settings
    private var charSettingId: BluetoothGattCharacteristic? = null
    private var charSettingValue: BluetoothGattCharacteristic? = null

    // History - Events
    private var charHistoryEventsCount: BluetoothGattCharacteristic? = null
    private var charHistoryEventsIndex: BluetoothGattCharacteristic? = null
    private var charHistoryEventsValue: BluetoothGattCharacteristic? = null

    // History - Alerts
    private var charHistoryAlertsCount: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsIndex: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsValue: BluetoothGattCharacteristic? = null

    // History - System
    private var charHistorySystemCount: BluetoothGattCharacteristic? = null
    private var charHistorySystemIndex: BluetoothGattCharacteristic? = null
    private var charHistorySystemValue: BluetoothGattCharacteristic? = null

    // Security
    private var charSecurityStatus: BluetoothGattCharacteristic? = null

    private val discoveredServices = mutableListOf<BluetoothGattService>()
    private var connectedDeviceMac: String? = null

    var onDisconnectCallback: ((Int) -> Unit)? = null

    private var _countersSynced = true
    /** True if the last decryptIfNeeded call failed (vs BLE read returning null) */
    var lastDecryptFailed = false
        private set
    var pumpCryptor: PumpCryptor? = null
        set(value) {
            field = value
            _countersSynced = (value == null)
        }

    fun resetCounterSync() {
        _countersSynced = false
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        discoveredServices.clear()
        discoveredServices.addAll(gatt.services)
        connectedDeviceMac = gatt.device.address

        log("Discovered ${gatt.services.size} services")

        gatt.services.forEach { service ->
            service.characteristics.forEach { char ->
                when (char.uuid) {
                    YpsoPumpUuids.CHAR_DEVICE_NAME -> charDeviceName = char
                    YpsoPumpUuids.CHAR_MANUFACTURER -> charManufacturer = char
                    YpsoPumpUuids.CHAR_SOFTWARE -> charSoftwareRevision = char
                    YpsoPumpUuids.CHAR_AUTH_PASSWORD -> charAuthPassword = char
                    YpsoPumpUuids.CHAR_MASTER_VERSION -> charMasterVersion = char
                    YpsoPumpUuids.CHAR_BOLUS_START_STOP -> charBolusStartStop = char
                    YpsoPumpUuids.CHAR_BOLUS_STATUS -> charBolusStatus = char
                    YpsoPumpUuids.CHAR_TBR_START_STOP -> charTbrStartStop = char
                    YpsoPumpUuids.CHAR_SYSTEM_STATUS -> charSystemStatus = char
                    YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION -> charBolusNotification = char
                    YpsoPumpUuids.CHAR_SYSTEM_DATE -> charSystemDate = char
                    YpsoPumpUuids.CHAR_SYSTEM_TIME -> charSystemTime = char
                    YpsoPumpUuids.CHAR_EXTENDED_READ -> charExtendedRead = char
                    YpsoPumpUuids.CHAR_SETTING_ID -> charSettingId = char
                    YpsoPumpUuids.CHAR_SETTING_VALUE -> charSettingValue = char
                    YpsoPumpUuids.Events.COUNT -> charHistoryEventsCount = char
                    YpsoPumpUuids.Events.INDEX -> charHistoryEventsIndex = char
                    YpsoPumpUuids.Events.VALUE -> charHistoryEventsValue = char
                    YpsoPumpUuids.Alerts.COUNT -> charHistoryAlertsCount = char
                    YpsoPumpUuids.Alerts.INDEX -> charHistoryAlertsIndex = char
                    YpsoPumpUuids.Alerts.VALUE -> charHistoryAlertsValue = char
                    YpsoPumpUuids.System.COUNT -> charHistorySystemCount = char
                    YpsoPumpUuids.System.INDEX -> charHistorySystemIndex = char
                    YpsoPumpUuids.System.VALUE -> charHistorySystemValue = char
                    YpsoPumpUuids.CHAR_SEC_STATUS -> charSecurityStatus = char
                }
            }
        }

        val hasSecurityService = gatt.getService(YpsoPumpUuids.SERVICE_SECURITY) != null
        val hasWriteService = gatt.getService(YpsoPumpUuids.SERVICE_WRITE) != null
        return hasSecurityService || hasWriteService || discoveredServices.isNotEmpty()
    }

    override fun onServicesInvalidated() {
        charDeviceName = null
        charManufacturer = null
        charSoftwareRevision = null
        charAuthPassword = null
        charMasterVersion = null
        charBolusStartStop = null
        charBolusStatus = null
        charTbrStartStop = null
        charSystemStatus = null
        charBolusNotification = null
        charSystemDate = null
        charSystemTime = null
        charExtendedRead = null
        charSettingId = null
        charSettingValue = null
        charHistoryEventsCount = null
        charHistoryEventsIndex = null
        charHistoryEventsValue = null
        charHistoryAlertsCount = null
        charHistoryAlertsIndex = null
        charHistoryAlertsValue = null
        charHistorySystemCount = null
        charHistorySystemIndex = null
        charHistorySystemValue = null
        charSecurityStatus = null
        discoveredServices.clear()
        connectedDeviceMac = null
        // Note: pumpCryptor is intentionally NOT nulled here — it persists across reconnects
    }

    override fun initialize() {
        log("Initializing connection...")

        enableNotificationOnChar(charBolusStatus, "BolusStatus")
        enableNotificationOnChar(charBolusNotification, "BolusNotification")
        enableNotificationOnChar(charSystemStatus, "SystemStatus")

        _isConnected.value = true
        log("Connection initialized")
    }

    private fun enableNotificationOnChar(char: BluetoothGattCharacteristic?, name: String) {
        char?.let {
            if (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                setNotificationCallback(it).with { _, data ->
                    val bytes = data.value ?: return@with
                    log("$name notification: ${bytes.toHexString()}")
                    emitData(DataEvent.ProBluetoothNotification(name, bytes))
                }
                enableNotifications(it).enqueue()
                log("Enabled $name notifications")
            } else if (it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                setNotificationCallback(it).with { _, data ->
                    val bytes = data.value ?: return@with
                    log("$name indication: ${bytes.toHexString()}")
                    emitData(DataEvent.ProBluetoothNotification(name, bytes))
                }
                enableIndications(it).enqueue()
                log("Enabled $name indications")
            }
        }
    }

    // ==================== AUTHENTICATION ====================

    private fun computeAuthPassword(macAddress: String): ByteArray {
        val macBytes = macAddress.replace(":", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val buf = macBytes + AUTH_SALT
        return MessageDigest.getInstance("MD5").digest(buf)
    }

    /**
     * Autentica la conexión BLE con la bomba.
     *
     * Calcula la contraseña como MD5(MAC_bytes + AUTH_SALT) y la escribe en la característica
     * [YpsoPumpUuids.CHAR_AUTH_PASSWORD]. La bomba acepta o rechaza la contraseña implícitamente:
     * si la escritura tiene éxito, la conexión queda autenticada y se pueden realizar lecturas
     * y escrituras cifradas.
     *
     * @param callback Llamado con `true` si la autenticación fue exitosa, `false` en caso contrario.
     */
    fun authenticate(callback: (Boolean) -> Unit) {
        val char = charAuthPassword
        if (char == null) {
            log("Auth password characteristic not found!")
            callback(false)
            return
        }

        val mac = connectedDeviceMac
        if (mac == null) {
            log("No connected device MAC address!")
            callback(false)
            return
        }

        val password = computeAuthPassword(mac)
        log("Authenticating with MD5 password for MAC $mac")

        writeCharacteristic(char, password, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ ->
                log("Auth password written successfully")
                _isAuthenticated.value = true
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    callback(true)
                }, 200)
            }
            .fail { _, status ->
                log("Auth write failed with status: $status")
                callback(false)
            }
            .enqueue()
    }

    fun authenticate(passkey: String, callback: (Boolean) -> Unit) {
        authenticate(callback)
    }

    // ==================== PUBLIC API ====================

    fun readDeviceName(callback: (String?) -> Unit) {
        charDeviceName?.let { char ->
            readCharacteristic(char).with { _, data ->
                callback(data.getStringValue(0))
            }.enqueue()
        } ?: callback(null)
    }

    fun readManufacturer(callback: (String?) -> Unit) {
        charManufacturer?.let { char ->
            readCharacteristic(char).with { _, data ->
                callback(data.getStringValue(0))
            }.enqueue()
        } ?: callback(null)
    }

    fun readSoftwareRevision(callback: (String?) -> Unit) {
        charSoftwareRevision?.let { char ->
            readCharacteristic(char).with { _, data ->
                val bytes = data.value
                callback(bytes?.let { formatVersion(it) } ?: "Unknown")
            }.enqueue()
        } ?: callback(null)
    }

    fun readMasterVersion(callback: (String?) -> Unit) {
        charMasterVersion?.let { char ->
            readCharacteristic(char).with { _, data ->
                callback(data.getStringValue(0))
            }.fail { _, _ ->
                callback(null)
            }.enqueue()
        } ?: callback(null)
    }

    fun readCharacteristic(uuid: UUID, callback: (ByteArray?) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(null)
            return
        }

        readCharacteristic(char).with { _, data ->
            callback(data.value)
        }.fail { _, _ ->
            callback(null)
        }.enqueue()
    }

    /**
     * Realiza una lectura multi-trama desde la bomba.
     *
     * Lee primero la característica identificada por [firstUuid]. Si el byte de cabecera indica
     * que hay más de una trama, continúa leyendo tramas adicionales desde la característica
     * de lectura extendida ([YpsoPumpUuids.CHAR_EXTENDED_READ]) hasta completar el mensaje.
     * Finalmente ensambla todas las tramas con [YpsoFraming.parseMultiFrameRead].
     *
     * Si alguna trama falla, se devuelve `null` (nunca datos parciales, ya que corromperían
     * el descifrado posterior).
     *
     * @param firstUuid UUID de la característica primaria de la que se inicia la lectura.
     * @param callback Llamado con el payload ensamblado (sin cabeceras de trama), o `null` si falló.
     */
    fun readExtended(firstUuid: UUID, callback: (ByteArray?) -> Unit) {
        val firstChar = findCharacteristic(firstUuid)
        val extChar = charExtendedRead
        if (firstChar == null) {
            callback(null)
            return
        }

        readCharacteristic(firstChar).with { _, data ->
            val firstFrame = data.value
            if (firstFrame == null || firstFrame.isEmpty()) {
                callback(null)
                return@with
            }

            val totalFrames = YpsoFraming.getTotalFrames(firstFrame[0])
            if (totalFrames <= 1) {
                callback(if (firstFrame.size > 1) firstFrame.copyOfRange(1, firstFrame.size) else byteArrayOf())
                return@with
            }

            if (extChar == null) {
                callback(if (firstFrame.size > 1) firstFrame.copyOfRange(1, firstFrame.size) else byteArrayOf())
                return@with
            }

            val frames = mutableListOf(firstFrame)
            readRemainingFrames(extChar, totalFrames - 1, frames) { allFrames ->
                if (allFrames == null) {
                    callback(null)
                } else {
                    callback(YpsoFraming.parseMultiFrameRead(allFrames))
                }
            }
        }.fail { _, _ ->
            callback(null)
        }.enqueue()
    }

    private fun readRemainingFrames(
        char: BluetoothGattCharacteristic,
        remaining: Int,
        frames: MutableList<ByteArray>,
        callback: (List<ByteArray>?) -> Unit
    ) {
        if (remaining <= 0) {
            callback(frames)
            return
        }

        readCharacteristic(char).with { _, data ->
            val frame = data.value
            if (frame != null) frames.add(frame)
            readRemainingFrames(char, remaining - 1, frames, callback)
        }.fail { _, status ->
            log("Frame read failed (status=$status), got ${frames.size} of ${frames.size + remaining} frames")
            callback(null) // Don't return partial data — it corrupts decrypt
        }.enqueue()
    }

    fun writeCharacteristic(uuid: UUID, data: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(false)
            return
        }

        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        writeCharacteristic(char, data, writeType)
            .with { _, _ -> callback(true) }
            .fail { _, _ -> callback(false) }
            .enqueue()
    }

    /**
     * Escribe un payload en la bomba usando el protocolo multi-trama de Ypsomed.
     *
     * Divide [payload] en tramas mediante [YpsoFraming.chunkPayload] y las escribe
     * secuencialmente en la característica indicada por [uuid]. Cada trama se envía solo
     * cuando la anterior ha sido confirmada por la bomba.
     *
     * @param uuid UUID de la característica BLE de destino.
     * @param payload Datos a enviar (sin cabeceras de trama; el framing se aplica aquí).
     * @param callback Llamado con `true` si todas las tramas se escribieron correctamente.
     */
    fun writeMultiFrame(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(false)
            return
        }

        val frames = YpsoFraming.chunkPayload(payload)
        log("Writing ${payload.size} bytes in ${frames.size} frames to $uuid")
        writeFramesSequentially(char, frames, 0, callback)
    }

    private fun writeFramesSequentially(
        char: BluetoothGattCharacteristic,
        frames: List<ByteArray>,
        index: Int,
        callback: (Boolean) -> Unit
    ) {
        if (index >= frames.size) {
            callback(true)
            return
        }

        writeCharacteristic(char, frames[index], BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ ->
                writeFramesSequentially(char, frames, index + 1, callback)
            }
            .fail { _, status ->
                log("Frame ${index + 1} write failed: $status")
                callback(false)
            }
            .enqueue()
    }

    // ==================== ENCRYPTION HELPERS ====================

    private fun encryptIfNeeded(data: ByteArray): ByteArray {
        val cryptor = pumpCryptor ?: return data
        val encrypted = cryptor.encrypt(data)
        log("Encrypted ${data.size} -> ${encrypted.size} bytes")
        return encrypted
    }

    private fun decryptIfNeeded(data: ByteArray): ByteArray? {
        val cryptor = pumpCryptor ?: return data
        lastDecryptFailed = false
        return try {
            val decrypted = cryptor.decrypt(data)
            if (!_countersSynced) {
                _countersSynced = true
                log("Counter sync OK (reboot=${cryptor.rebootCounter}, read=${cryptor.readCounter}, write=${cryptor.writeCounter})")
            }
            decrypted
        } catch (e: Exception) {
            lastDecryptFailed = true
            log("Decryption FAILED (${data.size}B, key=${cryptor.sharedKey.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }}...): ${e.message}")
            null
        }
    }

    private fun ensureCountersSynced(callback: () -> Unit) {
        if (_countersSynced || pumpCryptor == null) {
            callback()
            return
        }
        log("Auto-syncing counters before encrypted write...")
        getSystemStatus { _ ->
            _countersSynced = true
            callback()
        }
    }

    // ==================== PUMP COMMANDS ====================

    /**
     * Envía un comando cifrado a la bomba a través de la característica indicada.
     *
     * El flujo es: sincronizar contadores si es necesario → añadir CRC con [YpsoCrc.appendCrc]
     * → cifrar con [PumpCryptor.encrypt] si hay criptador activo → trocear en tramas con
     * [YpsoFraming.chunkPayload] → escribir secuencialmente en [uuid].
     *
     * @param uuid UUID de la característica BLE de destino.
     * @param payload Datos del comando (sin CRC ni cifrado; ambos se aplican aquí).
     * @param callback Llamado con `true` si el envío fue exitoso.
     */
    fun sendCommand(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(false)
            return
        }

        ensureCountersSynced {
            val withCrc = YpsoCrc.appendCrc(payload)
            val dataToSend = encryptIfNeeded(withCrc)
            val frames = YpsoFraming.chunkPayload(dataToSend)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    /**
     * Lee una respuesta cifrada de la bomba, la descifra y elimina el CRC.
     *
     * Realiza una lectura multi-trama con [readExtended], descifra el resultado con
     * [PumpCryptor.decrypt] si hay criptador activo, y si el CRC al final es válido lo elimina
     * antes de devolver el payload limpio.
     *
     * @param uuid UUID de la característica BLE a leer.
     * @param callback Llamado con el payload descifrado sin CRC, o `null` si la operación falló.
     */
    fun readCommand(uuid: UUID, callback: (ByteArray?) -> Unit) {
        readExtended(uuid) { rawData ->
            if (rawData == null) {
                callback(null)
                return@readExtended
            }
            val data = decryptIfNeeded(rawData)
            if (data == null) {
                callback(null)
                return@readExtended
            }
            if (YpsoCrc.isValid(data)) {
                callback(data.copyOfRange(0, data.size - 2))
            } else {
                callback(data)
            }
        }
    }

    /**
     * Envía un comando de bolo a la característica dedicada [YpsoPumpUuids.CHAR_BOLUS_START_STOP].
     *
     * Equivalente a [sendCommand] pero siempre usa la característica de bolo. El flujo es:
     * sincronizar contadores → añadir CRC → cifrar → trocear en tramas → escribir.
     *
     * @param payload Payload del bolo de 13 bytes (totalScaled, durationMinutes, immediateScaled, bolusType).
     * @param callback Llamado con `true` si el comando fue aceptado por la bomba.
     */
    fun sendBolusCommand(payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = charBolusStartStop
        if (char == null) {
            callback(false)
            return
        }

        ensureCountersSynced {
            val withCrc = YpsoCrc.appendCrc(payload)
            val dataToSend = encryptIfNeeded(withCrc)
            val frames = YpsoFraming.chunkPayload(dataToSend)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    /**
     * Lee y descifra el estado del sistema de la bomba desde [YpsoPumpUuids.CHAR_SYSTEM_STATUS].
     *
     * El payload descifrado contiene: modo de entrega (1 byte), insulina restante en centi-unidades
     * (4 bytes LE), nivel de batería en porcentaje (1 byte). Esta lectura también sincroniza
     * los contadores de cifrado (reboot_counter, read_counter, write_counter) con los de la bomba,
     * lo cual es obligatorio antes de cualquier escritura cifrada.
     *
     * @param callback Llamado con un [SystemStatusData] si la lectura y el descifrado fueron
     *                 correctos, o `null` en caso de error.
     */
    fun getSystemStatus(callback: (SystemStatusData?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_SYSTEM_STATUS) { rawData ->
            if (rawData == null) {
                callback(null)
                return@readExtended
            }
            val data = decryptIfNeeded(rawData)
            if (data == null) {
                callback(null)
                return@readExtended
            }

            if (YpsoCrc.isValid(data)) {
                val payload = data.copyOfRange(0, data.size - 2)
                if (payload.size >= 6) {
                    val mode = payload[0].toInt() and 0xFF
                    val insulinRaw = ByteBuffer.wrap(payload, 1, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int
                    val battery = payload[5].toInt() and 0xFF
                    val status = SystemStatusData(
                        deliveryMode = mode,
                        deliveryModeName = DeliveryMode.name(mode),
                        insulinRemaining = insulinRaw / 100f,
                        batteryPercent = battery
                    )
                    log("Status: ${status.deliveryModeName}, Insulin: ${status.insulinRemaining}U, Battery: ${status.batteryPercent}%")
                    emitData(DataEvent.StatusNotification(data))
                    callback(status)
                } else {
                    callback(null)
                }
            } else {
                callback(null)
            }
        }
    }

    /**
     * Construye y envía un comando de inicio de bolo a la bomba.
     *
     * El payload de 13 bytes tiene el formato (little-endian):
     * `totalScaled(4B) + durationMinutes(4B) + immediateScaled(4B) + bolusType(1B)`.
     * - `totalScaled` = round(totalUnits * 100), acotado entre 1 y 2500.
     * - `immediateScaled` = round(immediateUnits * 100), acotado entre 0 y totalScaled.
     * - `bolusType` = 1 para bolo estándar (durationMinutes == 0), 2 para extendido/multionda.
     *
     * @param totalUnits Cantidad total de insulina en unidades.
     * @param durationMinutes Duración en minutos para bolo extendido o multionda; 0 para bolo estándar.
     * @param immediateUnits Fracción inmediata para bolo multionda; 0 para bolo estándar o extendido.
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun startBolus(totalUnits: Float, durationMinutes: Int = 0, immediateUnits: Float = 0f, callback: (Boolean) -> Unit) {
        val totalScaled = Math.round(totalUnits * 100).coerceIn(1, 2500)
        val immediateScaled = Math.round(immediateUnits * 100).coerceIn(0, totalScaled)
        val bolusType: Byte = if (durationMinutes == 0) 1 else 2

        val payload = ByteBuffer.allocate(13)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(totalScaled)
            .putInt(durationMinutes)
            .putInt(immediateScaled)
            .put(bolusType)
            .array()

        log("Starting bolus: ${"%.2f".format(totalUnits)}U (scaled=$totalScaled), duration=${durationMinutes}min, type=$bolusType")
        sendBolusCommand(payload, callback)
    }

    /**
     * Envía un comando de cancelación de bolo a la bomba.
     *
     * Construye un payload de 13 bytes con todos los campos a cero excepto el tipo de bolo,
     * que indica qué bolo cancelar: "fast" (tipo 1), "extended" o "combined" (tipo 2).
     *
     * @param kind Tipo de bolo a cancelar: "fast", "extended" o "combined".
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun cancelBolus(kind: String = "fast", callback: (Boolean) -> Unit) {
        val typeMap = mapOf("fast" to 1, "extended" to 2, "combined" to 2)
        val bolusType = typeMap[kind] ?: 1
        val payload = ByteArray(13).also { it[12] = bolusType.toByte() }
        sendBolusCommand(payload, callback)
    }

    /**
     * Sincroniza la fecha y la hora del sistema Android con la bomba.
     *
     * Escribe primero la fecha cifrada (año 2B LE + mes 1B + día 1B) en
     * [YpsoPumpUuids.CHAR_SYSTEM_DATE] y, si tiene éxito, la hora cifrada
     * (hora 1B + minutos 1B + segundos 1B) en [YpsoPumpUuids.CHAR_SYSTEM_TIME].
     * Ambos payloads llevan CRC y se cifran antes de enviarse.
     *
     * @param callback Llamado con `true` si tanto la fecha como la hora se escribieron correctamente.
     */
    fun syncTime(callback: (Boolean) -> Unit) {
        val now = java.util.Calendar.getInstance()

        val datePayload = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(now.get(java.util.Calendar.YEAR).toShort())
            .put((now.get(java.util.Calendar.MONTH) + 1).toByte())
            .put(now.get(java.util.Calendar.DAY_OF_MONTH).toByte())
            .array()

        val timePayload = byteArrayOf(
            now.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            now.get(java.util.Calendar.MINUTE).toByte(),
            now.get(java.util.Calendar.SECOND).toByte()
        )

        val dateChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_DATE)
        if (dateChar == null) {
            callback(false)
            return
        }

        ensureCountersSynced {
            val dateWithCrc = encryptIfNeeded(YpsoCrc.appendCrc(datePayload))
            val dateFrames = YpsoFraming.chunkPayload(dateWithCrc)

            writeFramesSequentially(dateChar, dateFrames, 0) { dateSuccess ->
                if (!dateSuccess) {
                    callback(false)
                    return@writeFramesSequentially
                }

                val timeWithCrc = encryptIfNeeded(YpsoCrc.appendCrc(timePayload))
                val timeFrames = YpsoFraming.chunkPayload(timeWithCrc)
                val timeChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_TIME)

                if (timeChar == null) {
                    callback(false)
                    return@writeFramesSequentially
                }

                writeFramesSequentially(timeChar, timeFrames, 0, callback)
            }
        }
    }

    /**
     * Lee y descifra el estado detallado del bolo desde [YpsoPumpUuids.CHAR_BOLUS_STATUS].
     *
     * El payload descifrado de 42 bytes sigue el formato `decode_bolus_status()` del SDK Python:
     * - Bloque rápido (fast, 13 bytes): status(1B) + sequence(4B) + injected(4B, /100) + total(4B, /100)
     * - Bloque lento (slow, 29 bytes): status(1B) + sequence(4B) + injected(4B) + total(4B)
     *   + fast_part_injected(4B) + fast_part_total(4B) + actual_duration(4B) + total_duration(4B)
     *
     * Todas las cantidades de insulina están en centi-unidades (se dividen por 100 para obtener U).
     *
     * @param callback Llamado con un [BolusStatusData] completo, o `null` si la operación falló
     *                 o el payload tiene menos de 13 bytes.
     */
    fun getBolusStatus(callback: (BolusStatusData?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_BOLUS_STATUS) { rawData ->
            if (rawData == null) {
                callback(null)
                return@readExtended
            }
            val data = decryptIfNeeded(rawData)
            if (data == null) {
                callback(null)
                return@readExtended
            }
            if (!YpsoCrc.isValid(data)) {
                callback(null)
                return@readExtended
            }

            val payload = data.copyOfRange(0, data.size - 2)
            if (payload.size < 13) {
                callback(null)
                return@readExtended
            }

            // Layout matches Python decode_bolus_status():
            // Fast: status(1B) + sequence(4B) + injected(4B,/100) + total(4B,/100) = 13B
            // Slow: status(1B) + sequence(4B) + injected(4B) + total(4B) + fast_part_injected(4B)
            //       + fast_part_total(4B) + actual_duration(4B) + total_duration(4B) = 29B
            // Total: 42 bytes
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val fastStatus = buf.get().toInt() and 0xFF
            val fastSequence = buf.int.toLong() and 0xFFFFFFFFL
            val fastInjected = buf.int / 100f
            val fastTotal = buf.int / 100f

            var slowStatus = 0; var slowSequence = 0L
            var slowInjected = 0f; var slowTotal = 0f
            var slowFastPartInjected = 0f; var slowFastPartTotal = 0f
            var actualDuration = 0; var totalDuration = 0

            if (payload.size >= 14) {
                slowStatus = buf.get().toInt() and 0xFF
                if (slowStatus != 0 && payload.size >= 42) {
                    slowSequence = buf.int.toLong() and 0xFFFFFFFFL
                    slowInjected = buf.int / 100f
                    slowTotal = buf.int / 100f
                    slowFastPartInjected = buf.int / 100f
                    slowFastPartTotal = buf.int / 100f
                    actualDuration = buf.int
                    totalDuration = buf.int
                }
            }

            callback(BolusStatusData(
                fastStatus = fastStatus,
                fastSequence = fastSequence,
                fastInjected = fastInjected,
                fastTotal = fastTotal,
                slowStatus = slowStatus,
                slowSequence = slowSequence,
                slowInjected = slowInjected,
                slowTotal = slowTotal,
                slowFastPartInjected = slowFastPartInjected,
                slowFastPartTotal = slowFastPartTotal,
                actualDuration = actualDuration,
                totalDuration = totalDuration
            ))
        }
    }

    // ==================== TBR ====================

    /**
     * Inicia una tasa basal temporal (TBR) en la bomba.
     *
     * Construye un payload GLB de 16 bytes en little-endian con el formato:
     * `percent(4B) + ~percent(4B) + durationMinutes(4B) + ~durationMinutes(4B)`,
     * donde `~` es el complemento bit a bit del valor (verificación de integridad del protocolo).
     * El porcentaje es un valor absoluto (p. ej. 25 para el 25%, NO 2500).
     *
     * @param percent Porcentaje de la tasa basal temporal (p. ej. 50 para 50%, 150 para 150%).
     * @param durationMinutes Duración de la TBR en minutos.
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun startTbr(percent: Int, durationMinutes: Int, callback: (Boolean) -> Unit) {
        val char = charTbrStartStop
        if (char == null) {
            callback(false)
            return
        }

        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(percent)
            .putInt(percent.inv())
            .putInt(durationMinutes)
            .putInt(durationMinutes.inv())
            .array()

        ensureCountersSynced {
            val dataToSend = encryptIfNeeded(payload)
            val frames = YpsoFraming.chunkPayload(dataToSend)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    /**
     * Cancela la tasa basal temporal (TBR) activa y restablece la tasa basal normal.
     *
     * Envía un payload GLB con percent=100 y duration=0, lo que equivale a volver al 100%
     * de la tasa basal programada (es decir, cancelar la TBR).
     *
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun cancelTbr(callback: (Boolean) -> Unit) {
        val char = charTbrStartStop
        if (char == null) {
            callback(false)
            return
        }

        val percent = 100
        val duration = 0
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(percent)
            .putInt(percent.inv())
            .putInt(duration)
            .putInt(duration.inv())
            .array()

        ensureCountersSynced {
            val dataToSend = encryptIfNeeded(payload)
            val frames = YpsoFraming.chunkPayload(dataToSend)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    // ==================== SETTINGS ====================

    /**
     * Lee el valor de un ajuste de la bomba por su índice.
     *
     * El protocolo requiere dos pasos: primero escribe el índice (codificado en formato GLB)
     * en [YpsoPumpUuids.CHAR_SETTING_ID] y luego lee el valor resultante de
     * [YpsoPumpUuids.CHAR_SETTING_VALUE]. El valor devuelto es un entero GLB descifrado.
     * El valor -1 (0xFFFFFFFF) indica una ranura sin programar.
     *
     * @param index Índice del ajuste a leer (ver [SettingsIndex]).
     * @param callback Llamado con el valor entero del ajuste, o `null` si la operación falló.
     */
    fun readSetting(index: Int, callback: (Int?) -> Unit) {
        val idChar = charSettingId
        val valChar = charSettingValue
        if (idChar == null || valChar == null) {
            callback(null)
            return
        }

        ensureCountersSynced {
            val indexPayload = YpsoGlb.encode(index)
            val dataToSend = encryptIfNeeded(indexPayload)
            val frames = YpsoFraming.chunkPayload(dataToSend)

            writeFramesSequentially(idChar, frames, 0) { success ->
                if (!success) {
                    callback(null)
                    return@writeFramesSequentially
                }

                readExtended(YpsoPumpUuids.CHAR_SETTING_VALUE) { rawData ->
                    if (rawData == null) {
                        log("readSetting($index): BLE read returned null")
                        callback(null)
                        return@readExtended
                    }

                    val data = decryptIfNeeded(rawData)
                    if (data == null) {
                        log("readSetting($index): decrypt failed")
                        callback(null)
                        return@readExtended
                    }
                    val glbValue = YpsoGlb.findInPayload(data)
                    log("readSetting($index): value=$glbValue (${data.size}B decrypted)")
                    callback(glbValue)
                }
            }
        }
    }

    /**
     * Escribe un valor en un ajuste de la bomba por su índice.
     *
     * Escribe primero el índice en [YpsoPumpUuids.CHAR_SETTING_ID] y luego el valor en
     * [YpsoPumpUuids.CHAR_SETTING_VALUE]. Ambos se codifican en formato GLB, se cifran
     * y se envían en tramas.
     *
     * @param index Índice del ajuste a escribir (ver [SettingsIndex]).
     * @param value Valor entero a escribir.
     * @param callback Llamado con `true` si ambas escrituras tuvieron éxito.
     */
    fun writeSetting(index: Int, value: Int, callback: (Boolean) -> Unit) {
        val idChar = charSettingId
        val valChar = charSettingValue
        if (idChar == null || valChar == null) {
            callback(false)
            return
        }

        ensureCountersSynced {
            val indexPayload = YpsoGlb.encode(index)
            val indexToSend = encryptIfNeeded(indexPayload)
            val indexFrames = YpsoFraming.chunkPayload(indexToSend)

            writeFramesSequentially(idChar, indexFrames, 0) { idSuccess ->
                if (!idSuccess) {
                    callback(false)
                    return@writeFramesSequentially
                }

                val valuePayload = YpsoGlb.encode(value)
                val valueToSend = encryptIfNeeded(valuePayload)
                val valueFrames = YpsoFraming.chunkPayload(valueToSend)

                writeFramesSequentially(valChar, valueFrames, 0, callback)
            }
        }
    }

    // ==================== BASAL PROFILES ====================

    /**
     * Lee el perfil basal completo de un programa (A o B) de la bomba.
     *
     * Lee 24 ajustes consecutivos (uno por hora del día) a partir del índice de inicio
     * correspondiente al programa ([SettingsIndex.PROGRAM_A_START] o [SettingsIndex.PROGRAM_B_START]).
     * Cada valor está en centi-unidades/hora (se divide por 100 para obtener U/h).
     * Las ranuras sin programar (valor -1) se devuelven como 0.0f.
     *
     * @param program Identificador del programa: 'A' o 'B'.
     * @param callback Llamado con una lista de 24 tasas en U/h, o `null` si alguna lectura falló.
     */
    fun readBasalProfile(program: Char, callback: (List<Float>?) -> Unit) {
        val startIndex = when (program) {
            'A' -> SettingsIndex.PROGRAM_A_START
            'B' -> SettingsIndex.PROGRAM_B_START
            else -> {
                callback(null)
                return
            }
        }

        val rates = mutableListOf<Float>()
        readBasalRateSequential(startIndex, startIndex + 23, rates, callback)
    }

    private fun readBasalRateSequential(
        currentIndex: Int,
        endIndex: Int,
        rates: MutableList<Float>,
        callback: (List<Float>?) -> Unit
    ) {
        if (currentIndex > endIndex) {
            log("Basal profile read complete: ${rates.size} rates, values=${rates.map { "%.2f".format(it) }}")
            callback(rates)
            return
        }

        readSetting(currentIndex) { value ->
            if (value != null) {
                if (value == -1) {  // 0xFFFFFFFF = unprogrammed slot
                    rates.add(0f)
                } else {
                    rates.add(value / 100f)
                }
                readBasalRateSequential(currentIndex + 1, endIndex, rates, callback)
            } else {
                log("Basal profile read failed at index $currentIndex (slot ${currentIndex - rates.size})")
                callback(null)
            }
        }
    }

    fun readActiveProgram(callback: (Char?) -> Unit) {
        readSetting(SettingsIndex.ACTIVE_PROGRAM) { value ->
            when (value) {
                SettingsIndex.PROGRAM_A_VALUE -> callback('A')
                SettingsIndex.PROGRAM_B_VALUE -> callback('B')
                else -> callback(null)
            }
        }
    }

    // ==================== HISTORY ====================

    fun readHistoryCount(countUuid: UUID, callback: (Int?) -> Unit) {
        readExtended(countUuid) { rawData ->
            if (rawData == null) {
                callback(null)
                return@readExtended
            }
            val data = decryptIfNeeded(rawData)
            if (data == null) {
                callback(null)
                return@readExtended
            }
            callback(YpsoGlb.findInPayload(data))
        }
    }

    private fun readHistoryEntry(
        indexChar: BluetoothGattCharacteristic,
        valueUuid: UUID,
        entryIndex: Int,
        callback: (HistoryEntry?) -> Unit
    ) {
        val indexPayload = YpsoGlb.encode(entryIndex)
        val dataToSend = encryptIfNeeded(indexPayload)
        val frames = YpsoFraming.chunkPayload(dataToSend)

        writeFramesSequentially(indexChar, frames, 0) { success ->
            if (!success) {
                callback(null)
                return@writeFramesSequentially
            }

            readExtended(valueUuid) { rawData ->
                if (rawData == null) {
                    callback(null)
                    return@readExtended
                }

                val data = decryptIfNeeded(rawData)
                if (data == null) {
                    callback(null)
                    return@readExtended
                }
                val payload = if (YpsoCrc.isValid(data)) {
                    data.copyOfRange(0, data.size - 2)
                } else {
                    data
                }

                if (payload.size >= 17) {
                    try {
                        callback(HistoryEntry.parse(payload))
                    } catch (e: Exception) {
                        callback(null)
                    }
                } else {
                    callback(null)
                }
            }
        }
    }

    /**
     * Lee un conjunto de entradas de historial de la bomba de forma genérica.
     *
     * Lee primero el contador total de entradas desde [countUuid], luego itera secuencialmente
     * desde el índice más antiguo relevante (según [maxEntries]) hasta el más reciente,
     * escribiendo cada índice en [indexChar] y leyendo el valor desde [valueUuid].
     * Las entradas se devuelven en orden cronológico inverso (más reciente primero).
     *
     * @param countUuid UUID de la característica que contiene el número total de entradas.
     * @param indexChar Característica en la que se escribe el índice de la entrada deseada.
     * @param valueUuid UUID de la característica desde la que se lee el valor de cada entrada.
     * @param maxEntries Número máximo de entradas a leer (0 para leer todas).
     * @param callback Llamado con la lista de [HistoryEntry] leídos (puede estar vacía).
     */
    fun readHistory(
        countUuid: UUID,
        indexChar: BluetoothGattCharacteristic?,
        valueUuid: UUID,
        maxEntries: Int = 50,
        callback: (List<HistoryEntry>) -> Unit
    ) {
        if (indexChar == null) {
            callback(emptyList())
            return
        }

        readHistoryCount(countUuid) { count ->
            if (count == null || count == 0) {
                callback(emptyList())
                return@readHistoryCount
            }

            val startIdx = if (maxEntries > 0) maxOf(0, count - maxEntries) else 0
            val entries = mutableListOf<HistoryEntry>()
            readHistoryEntriesSequential(indexChar, valueUuid, startIdx, count - 1, entries, callback)
        }
    }

    private fun readHistoryEntriesSequential(
        indexChar: BluetoothGattCharacteristic,
        valueUuid: UUID,
        currentIdx: Int,
        lastIdx: Int,
        entries: MutableList<HistoryEntry>,
        callback: (List<HistoryEntry>) -> Unit
    ) {
        if (currentIdx > lastIdx) {
            callback(entries.reversed())
            return
        }

        readHistoryEntry(indexChar, valueUuid, currentIdx) { entry ->
            if (entry != null) entries.add(entry)
            readHistoryEntriesSequential(indexChar, valueUuid, currentIdx + 1, lastIdx, entries, callback)
        }
    }

    fun readHistoryEventsCount(callback: (Int?) -> Unit) {
        readHistoryCount(YpsoPumpUuids.Events.COUNT, callback)
    }

    fun readHistoryAlertsCount(callback: (Int?) -> Unit) {
        readHistoryCount(YpsoPumpUuids.Alerts.COUNT, callback)
    }

    /**
     * Lee las entradas más recientes del historial de eventos de la bomba.
     *
     * Usa las características del grupo [YpsoPumpUuids.Events] (COUNT, INDEX, VALUE).
     *
     * @param maxEntries Número máximo de eventos a leer.
     * @param callback Llamado con la lista de [HistoryEntry] de eventos.
     */
    fun readHistoryEvents(maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit) {
        readHistory(YpsoPumpUuids.Events.COUNT, charHistoryEventsIndex, YpsoPumpUuids.Events.VALUE, maxEntries, callback)
    }

    /**
     * Lee las entradas más recientes del historial de alertas de la bomba.
     *
     * Usa las características del grupo [YpsoPumpUuids.Alerts] (COUNT, INDEX, VALUE).
     *
     * @param maxEntries Número máximo de alertas a leer.
     * @param callback Llamado con la lista de [HistoryEntry] de alertas.
     */
    fun readHistoryAlerts(maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit) {
        readHistory(YpsoPumpUuids.Alerts.COUNT, charHistoryAlertsIndex, YpsoPumpUuids.Alerts.VALUE, maxEntries, callback)
    }

    fun readHistorySystem(maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit) {
        readHistory(YpsoPumpUuids.System.COUNT, charHistorySystemIndex, YpsoPumpUuids.System.VALUE, maxEntries, callback)
    }

    // ==================== SECURITY STATUS ====================

    fun readSecurityStatus(callback: (ByteArray?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_SEC_STATUS) { rawData ->
            if (rawData != null) {
                val data = decryptIfNeeded(rawData)
                callback(data)
            } else {
                callback(null)
            }
        }
    }

    // ==================== HELPERS ====================

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        discoveredServices.forEach { service ->
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun formatVersion(bytes: ByteArray): String {
        return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logEvents.tryEmit(LogEvent(System.currentTimeMillis(), message))
    }

    private fun emitData(event: DataEvent) {
        _dataReceived.tryEmit(event)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }

    override fun close() {
        _isConnected.value = false
        _isAuthenticated.value = false
        pumpCryptor = null
        super.close()
    }
}

/**
 * Evento de log interno emitido por [YpsoBleManager] para cada mensaje de depuración.
 *
 * Se acumula en el flujo [YpsoBleManager.logEvents] con repetición de los últimos 100 mensajes,
 * lo que permite a los observadores recibir el historial reciente al suscribirse.
 *
 * @property timestamp Marca de tiempo en milisegundos (System.currentTimeMillis()).
 * @property message Texto del mensaje de log.
 */
internal data class LogEvent(val timestamp: Long, val message: String)

/**
 * Evento de datos internos emitido por [YpsoBleManager] cuando se recibe información
 * relevante de la bomba (notificaciones BLE, respuestas de autenticación, estado del sistema).
 *
 * Se propaga a través del flujo [YpsoBleManager.dataReceived].
 */
internal sealed class DataEvent {
    /** Respuesta recibida durante el proceso de autenticación. */
    data class AuthResponse(val data: ByteArray) : DataEvent()

    /** Notificación de estado del sistema recibida desde la característica de estado. */
    data class StatusNotification(val data: ByteArray) : DataEvent()

    /**
     * Notificación BLE genérica recibida desde cualquier característica con NOTIFY/INDICATE.
     *
     * @property characteristic Nombre descriptivo de la característica (p. ej. "BolusNotification").
     * @property data Bytes crudos recibidos en la notificación.
     */
    data class ProBluetoothNotification(val characteristic: String, val data: ByteArray) : DataEvent()
}
