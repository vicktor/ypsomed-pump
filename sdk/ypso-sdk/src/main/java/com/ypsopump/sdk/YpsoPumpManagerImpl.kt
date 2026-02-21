package com.ypsopump.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pump.common.*
import com.ypsopump.sdk.internal.ble.DataEvent
import com.ypsopump.sdk.internal.ble.YpsoBleManager
import com.ypsopump.sdk.internal.ble.YpsoPumpUuids
import com.ypsopump.sdk.internal.connection.PollManager
import com.ypsopump.sdk.internal.crypto.PumpCryptor
import com.ypsopump.sdk.internal.crypto.YpsoCrypto
import com.ypsopump.sdk.internal.data.BolusNotification
import com.ypsopump.sdk.internal.data.BolusStatusData
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.keyexchange.RelayClient
import com.ypsopump.sdk.internal.keyexchange.YpsoKeyExchange
import com.ypsopump.sdk.internal.parsing.PumpEventProcessor
import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.DeliveryMode
import com.ypsopump.sdk.internal.protocol.SettingsIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import kotlin.coroutines.resume

/**
 * Implementación principal de [PumpManager] para la bomba YpsoPump.
 *
 * Esta clase orquesta el ciclo de vida completo de la comunicación con la bomba:
 *
 * **Conexión y emparejamiento:**
 * Inicia un escaneo BLE buscando dispositivos con el prefijo "YpsoPump_", gestiona el enlace
 * Bluetooth (bonding) y se conecta al dispositivo. Una vez conectado, autentica la sesión y
 * carga la clave de cifrado almacenada en caché.
 *
 * **Cifrado y renovación de clave:**
 * La comunicación cifrada usa XChaCha20-Poly1305 con una clave compartida derivada mediante
 * ECDH (X25519) + HChaCha20. La clave tiene una validez de 28 días. Si el descifrado falla,
 * se detecta automáticamente la expiración y se realiza un intercambio de claves vía servidor
 * relay (Proregia) sin intervención del usuario.
 *
 * **Patrón connect-on-demand:**
 * Después de la inicialización, la conexión BLE no se mantiene permanentemente. Cada comando
 * usa [withPumpConnection] para conectarse, autenticarse, sincronizar contadores y ejecutar
 * el bloque, desconectándose al finalizar. Esto evita problemas de timeout y ahorra batería.
 *
 * **Fiabilidad de comandos críticos:**
 * Los comandos de bolo y TBR se ejecutan dentro de [withCriticalRetry], que reintenta hasta
 * 3 veces con backoff exponencial ante fallos de conexión o cifrado.
 *
 * **Sondeo periódico:**
 * Un [PollManager] interno llama a [pollPumpStatus] periódicamente para mantener el estado
 * actualizado y detectar nuevos eventos de historial (bolos, alertas).
 *
 * El punto de entrada público es [create], que devuelve una instancia de [PumpManager].
 */
class YpsoPumpManagerImpl private constructor(
    private val context: Context
) : PumpManager {

    companion object {
        private const val TAG = "YpsoPumpManagerImpl"

        fun create(context: Context): PumpManager = YpsoPumpManagerImpl(context.applicationContext)
    }

    // Coroutine scope
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    // BLE
    private val bleManager = YpsoBleManager(context)
    private val keyExchange = YpsoKeyExchange(context, bleManager)
    private val relayClient = RelayClient()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scanner = BluetoothLeScannerCompat.getScanner()
    private var isScanning = false

    // Event processing
    private val eventProcessor = PumpEventProcessor()
    private val pollManager = PollManager(context, scope) { pollPumpStatus() }

    // Device tracking
    private var selectedDevice: BluetoothDevice? = null
    private var connectedDeviceName: String? = null
    private val deviceMap = mutableMapOf<String, BluetoothDevice>()
    private var consecutivePollFailures = 0

    // Bond handling
    private var pendingBondAddress: String? = null
    private var bondReceiver: BroadcastReceiver? = null

    // State flows
    private val _connectionState = MutableStateFlow<PumpConnectionState>(PumpConnectionState.Disconnected)
    override val connectionState: StateFlow<PumpConnectionState> = _connectionState

    private val _pumpStatus = MutableStateFlow<PumpStatus?>(null)
    override val pumpStatus: StateFlow<PumpStatus?> = _pumpStatus

    private val _pumpEvents = MutableSharedFlow<PumpEvent>(replay = 10)
    override val pumpEvents: SharedFlow<PumpEvent> = _pumpEvents

    // Logs exposed for service to forward
    private val _logs = MutableSharedFlow<String>(replay = 100)
    val logs: SharedFlow<String> = _logs

    // Toast messages for user-facing errors
    private val _userMessages = MutableSharedFlow<String>()
    val userMessages: SharedFlow<String> = _userMessages

    // Key exchange state
    private val _needsKeyExchange = MutableStateFlow(false)
    val needsKeyExchange: StateFlow<Boolean> = _needsKeyExchange

    private val _activeProfile = MutableStateFlow<Char?>(null)
    val activeProfile: StateFlow<Char?> = _activeProfile

    private val _profileRates = MutableStateFlow<List<Float>>(emptyList())
    val profileRates: StateFlow<List<Float>> = _profileRates

    override val isPaired: Boolean get() = loadDeviceMac() != null
    override val pairedAddress: String? get() = loadDeviceMac()

    // Capabilities
    override val maxBasalProfiles: Int = 2
    override val maxBolusAmount: Double = 25.0
    override val tbrPercentRange: IntRange = 0..200

    init {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter

        // Wire disconnect callback (logging only — reconnection is handled per-command)
        bleManager.onDisconnectCallback = { reason ->
            log("BLE disconnect (reason=$reason)")
        }

        // Forward BLE data events
        scope.launch {
            bleManager.dataReceived.collect { event ->
                handleDataEvent(event)
            }
        }

        // Track BLE connection state — in connect-on-demand mode, BLE disconnects are normal
        // Only set Disconnected if we're in Initializing state (initial pairing failed)
        scope.launch {
            bleManager.isConnected.collect { connected ->
                if (!connected && _connectionState.value is PumpConnectionState.Initializing) {
                    _connectionState.value = PumpConnectionState.Disconnected
                }
            }
        }

        registerBondReceiver()
    }

    // ==================== CONNECTION ====================

    /**
     * Inicia la conexión con la bomba YpsoPump.
     *
     * Lanza un escaneo BLE buscando dispositivos cuyo nombre empiece por "YpsoPump_".
     * Al encontrar uno, detiene el escaneo, gestiona el bonding si es necesario,
     * conecta via [YpsoBleManager], autentica y carga la clave de cifrado.
     * Si la clave no existe o ha expirado, intenta realizar el intercambio automático de claves.
     *
     * Si ya hay una conexión en curso (Scanning, Connecting o Initializing), no hace nada.
     */
    override suspend fun connect() {
        if (_connectionState.value is PumpConnectionState.Scanning ||
            _connectionState.value is PumpConnectionState.Connecting ||
            _connectionState.value is PumpConnectionState.Initializing
        ) {
            log("Connection already in progress")
            return
        }

        if (!hasBluetoothPermissions()) {
            _connectionState.value = PumpConnectionState.Error("Missing Bluetooth permissions")
            return
        }

        _needsKeyExchange.value = false
        eventProcessor.reset()
        log("=== Auto-Connect Starting ===")

        _connectionState.value = PumpConnectionState.Scanning
        startScan()
    }

    /**
     * Desconecta la bomba y limpia el estado local.
     *
     * Detiene el sondeo periódico, cancela cualquier escaneo activo, desconecta el BLE,
     * limpia la caché del dispositivo y restablece todos los flujos de estado a sus valores
     * iniciales. Equivale a "desemparejar" desde el punto de vista del SDK.
     */
    override suspend fun disconnect() {
        log("Disconnecting (unpair)...")
        pollManager.stop()
        stopScan()
        bleManager.disconnect().enqueue()
        clearDeviceCache()
        _connectionState.value = PumpConnectionState.Disconnected
        _pumpStatus.value = null
        _activeProfile.value = null
        _profileRates.value = emptyList()
        _needsKeyExchange.value = false
        selectedDevice = null
    }

    override suspend fun confirmPairing() {
        // Bond confirmation is handled via Android system dialog
    }

    override suspend fun rejectPairing() {
        disconnect()
    }

    /**
     * Desempareja completamente la bomba, eliminando también las claves de cifrado almacenadas.
     *
     * Llama a [disconnect] y luego borra las preferencias compartidas de criptografía,
     * forzando un nuevo intercambio de claves en la próxima conexión.
     */
    override suspend fun unpair() {
        disconnect()
        // Clear cached keys
        context.getSharedPreferences("ypso_crypto", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ==================== SCANNING ====================

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return

        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = PumpConnectionState.Error("Bluetooth is disabled")
            return
        }

        deviceMap.clear()
        log("Starting BLE scan...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(listOf<ScanFilter>(), settings, scanCallback)
            isScanning = true
        } catch (e: SecurityException) {
            _connectionState.value = PumpConnectionState.Error("Permission denied")
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try {
            scanner.stopScan(scanCallback)
            isScanning = false
        } catch (_: SecurityException) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: return
            if (!name.startsWith(YpsoBleManager.YPSOPUMP_NAME_PREFIX)) return

            val address = device.address
            if (!deviceMap.containsKey(address)) {
                log("Found: $name ($address) RSSI: ${result.rssi}")
                deviceMap[address] = device
                connectToDevice(device, name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _connectionState.value = PumpConnectionState.Error("Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, name: String) {
        stopScan()
        selectedDevice = device
        connectedDeviceName = name
        saveDeviceMac(device.address)
        saveDeviceName(name)
        _connectionState.value = PumpConnectionState.Connecting
        log("Connecting to $name...")

        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectAfterBond(device, name)
            BluetoothDevice.BOND_BONDING -> {
                pendingBondAddress = device.address
            }
            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = device.address
                val started = device.createBond()
                if (!started) {
                    pendingBondAddress = null
                    connectAfterBond(device, name)
                }
            }
            else -> connectAfterBond(device, name)
        }
    }

    private fun connectAfterBond(device: BluetoothDevice, name: String) {
        bleManager.connect(device)
            .useAutoConnect(false)
            .retry(3, 200)
            .done { _: BluetoothDevice ->
                log("Connected to $name")
                _connectionState.value = PumpConnectionState.Initializing
                readPumpInfo()
                authenticateAndLoadKey()
            }
            .fail { _: BluetoothDevice, status: Int ->
                log("Connection failed: $status")
                _connectionState.value = PumpConnectionState.Error("Connection failed: $status")
            }
            .enqueue()
    }

    // ==================== DIRECT CONNECT (by MAC, no scan) ====================

    @SuppressLint("MissingPermission")
    private suspend fun connectDirect(mac: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(mac) ?: return false
        selectedDevice = device
        connectedDeviceName = loadDeviceName() ?: "YpsoPump"
        return suspendCancellableCoroutine { cont ->
            bleManager.connect(device)
                .useAutoConnect(false)
                .retry(2, 300)
                .done { _ -> cont.resume(true) }
                .fail { _, _ -> cont.resume(false) }
                .enqueue()
        }
    }

    // ==================== CONNECT-ON-DEMAND WRAPPER ====================

    private val pumpMutex = Mutex()
    private val _keyRenewalInProgress = MutableStateFlow(false)
    val keyRenewalInProgress: StateFlow<Boolean> = _keyRenewalInProgress

    private class EncryptionKeyExpiredException : Exception("Encryption key expired or invalid")
    private class BleCommException(msg: String) : Exception(msg)

    /**
     * Patrón connect-on-demand: conecta, autentica, sincroniza contadores y ejecuta [block].
     *
     * Secuencia de pasos protegida por [pumpMutex] para garantizar acceso exclusivo:
     * 1. Conecta directamente a la MAC guardada con [connectDirect].
     * 2. Autentica con MD5(MAC + salt) mediante [suspendAuth].
     * 3. Introduce una pausa de 300 ms (la bomba necesita tiempo tras la autenticación).
     * 4. Carga el [PumpCryptor] desde las preferencias; si no existe, lanza [EncryptionKeyExpiredException].
     * 5. Lee [getSystemStatus] para sincronizar los contadores de cifrado con la bomba.
     *    Si el descifrado falla, lanza [EncryptionKeyExpiredException] inmediatamente.
     *    Si es un fallo BLE transitorio, reintenta hasta 3 veces.
     * 6. Actualiza [_pumpStatus] con el estado recibido.
     * 7. Ejecuta [block] con el [SystemStatusData] obtenido.
     * 8. Desconecta en el bloque `finally` siempre que sea posible.
     *
     * @throws EncryptionKeyExpiredException si la clave de cifrado no existe o el descifrado falla.
     * @throws BleCommException si la conexión, la autenticación o las lecturas BLE fallan.
     */
    private suspend fun <T> withPumpConnection(block: suspend (SystemStatusData) -> T): T = pumpMutex.withLock {
        val mac = loadDeviceMac() ?: throw IllegalStateException("No paired device")
        try {
            val connected = connectDirect(mac)
            if (!connected) throw BleCommException("Connect failed")
            val authOk = suspendAuth()
            if (!authOk) throw BleCommException("Auth failed")
            delay(300) // pump needs time after auth before encrypted reads work
            // Restore cryptor from prefs + sync counters
            val cryptor = keyExchange.loadCryptor()
            if (cryptor == null) throw EncryptionKeyExpiredException()
            log("Loaded key=${cryptor.sharedKey.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }}..., reboot=${cryptor.rebootCounter}, read=${cryptor.readCounter}, write=${cryptor.writeCounter}")
            bleManager.pumpCryptor = cryptor
            bleManager.resetCounterSync()
            // Read system status to sync counters — detect key expiry immediately
            var status: SystemStatusData? = suspendGetSystemStatus()
            if (status == null && bleManager.lastDecryptFailed) {
                // Decrypt failed = key is dead, renew immediately (no retries)
                log("Decrypt failed on first read — key expired, renewing immediately")
                throw EncryptionKeyExpiredException()
            }
            // If BLE glitch (not decrypt), retry up to 2 more times
            if (status == null) {
                for (attempt in 2..3) {
                    log("getSystemStatus BLE retry $attempt/3...")
                    delay(500)
                    status = suspendGetSystemStatus()
                    if (status != null) break
                    if (bleManager.lastDecryptFailed) {
                        log("Decrypt failed on retry — key expired")
                        throw EncryptionKeyExpiredException()
                    }
                }
            }
            if (status == null) {
                log("BLE reads failed 3x but no decrypt error — transient BLE issue")
                throw BleCommException("System status read failed (BLE)")
            }
            updateStatusFromSystem(status)
            block(status)
        } finally {
            suspendDisconnect()
        }
    }

    /**
     * Envuelve [withPumpConnection] con renovación automática de clave ante expiración.
     *
     * Si [withPumpConnection] lanza [EncryptionKeyExpiredException], espera 1 segundo para
     * que la pila BLE se estabilice, activa [_keyRenewalInProgress], ejecuta
     * [suspendAutoKeyExchange] para obtener una nueva clave vía servidor relay y, si tiene
     * éxito, reintenta [withPumpConnection] una vez más. Al finalizar (con éxito o error),
     * [_keyRenewalInProgress] vuelve a `false`.
     *
     * @throws IllegalStateException si la renovación de clave falla.
     */
    private suspend fun <T> withPumpConnectionAndKeyRetry(block: suspend (SystemStatusData) -> T): T {
        return try {
            withPumpConnection(block)
        } catch (e: EncryptionKeyExpiredException) {
            // withPumpConnection already disconnected synchronously in finally,
            // but give BLE stack time to settle before reconnecting
            delay(1000)
            _keyRenewalInProgress.value = true
            try {
                val renewed = suspendAutoKeyExchange()
                if (!renewed) throw IllegalStateException("Key renewal failed")
                // suspendAutoKeyExchange disconnects in its own finally,
                // wait for BLE to settle before the retry connection
                delay(1000)
                withPumpConnection(block)
            } finally {
                _keyRenewalInProgress.value = false
            }
        }
    }

    /**
     * Reintenta operaciones críticas de la bomba (bolo, TBR) hasta [maxAttempts] veces.
     *
     * Las excepciones a nivel de conexión o cifrado indican que el comando nunca llegó
     * a la bomba, por lo que el reintento es seguro. Entre intentos se aplica un backoff
     * lineal de `2000 ms * intento`. [CancellationException] nunca se captura y se propaga
     * siempre, permitiendo la cancelación de corrutinas.
     *
     * @param operationName Nombre descriptivo de la operación (usado en los logs).
     * @param maxAttempts Número máximo de intentos (por defecto 3).
     * @param block Bloque suspendido a ejecutar; puede lanzar cualquier excepción.
     * @return El resultado de [block] si algún intento tuvo éxito.
     * @throws Exception La última excepción capturada si todos los intentos fallan.
     */
    private suspend fun <T> withCriticalRetry(
        operationName: String,
        maxAttempts: Int = 3,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                log("$operationName attempt $attempt/$maxAttempts failed: ${e.message}")
                if (attempt < maxAttempts) {
                    delay(2000L * attempt)
                }
            }
        }
        throw lastException!!
    }

    /** Disconnect BLE and wait for it to complete (not fire-and-forget). */
    private suspend fun suspendDisconnect() {
        if (!bleManager.isConnected.value) return
        suspendCancellableCoroutine { cont ->
            bleManager.disconnect()
                .done { cont.resume(Unit) }
                .fail { _, _ -> cont.resume(Unit) }
                .enqueue()
        }
        // Small delay for BLE stack to fully release resources
        delay(300)
    }

    // ==================== AUTHENTICATION & KEY LOADING ====================

    private fun authenticateAndLoadKey() {
        bleManager.authenticate { success ->
            if (!success) {
                log("Authentication failed")
                _connectionState.value = PumpConnectionState.Error("Authentication failed")
                return@authenticate
            }

            log("Authenticated")

            val cachedCryptor = keyExchange.loadCryptor()
            if (cachedCryptor != null) {
                bleManager.pumpCryptor = cachedCryptor
                log("Restored cached key")

                // Retry status read before declaring key invalid (could be transient BLE error)
                validateKeyWithRetry(retries = 3)
            } else {
                log("No cached key, attempting auto key exchange")
                autoRecoverKey()
            }
        }
    }

    private fun validateKeyWithRetry(retries: Int, attempt: Int = 1) {
        bleManager.getSystemStatus { status ->
            if (status != null) {
                onKeyValidated(status)
            } else if (attempt < retries) {
                log("Key validation attempt $attempt/$retries failed, retrying...")
                scope.launch {
                    delay(500)
                    validateKeyWithRetry(retries, attempt + 1)
                }
            } else {
                log("Key validation failed after $retries attempts, attempting auto key exchange")
                bleManager.pumpCryptor = null
                autoRecoverKey()
            }
        }
    }

    private fun onKeyValidated(status: SystemStatusData) {
        updateStatusFromSystem(status)
        log("Counter sync OK (reboot=${bleManager.pumpCryptor?.rebootCounter})")

        bleManager.syncTime { timeOk ->
            if (timeOk) log("Time synced")
            bleManager.readActiveProgram { program ->
                if (program != null) _activeProfile.value = program
            }
            // Disconnect BLE — we're now in connect-on-demand mode
            bleManager.disconnect().enqueue()
            _connectionState.value = PumpConnectionState.Ready
            log("=== Pairing Complete — ready for on-demand connections ===")
            emitEvent(PumpEvent.ConnectionRestored("YpsoPump"))
            pollManager.start()
        }
    }

    private fun autoRecoverKey() {
        val savedUrl = loadRelayUrl()
        if (savedUrl.isNullOrBlank()) {
            log("No relay URL saved — manual key exchange needed")
            _needsKeyExchange.value = true
            _connectionState.value = PumpConnectionState.Ready
            return
        }

        log("Attempting auto key exchange via relay...")
        performAutoKeyExchange(savedUrl)
    }

    private fun performAutoKeyExchange(relayUrl: String) {
        val serial = extractSerial()
        if (serial == null) {
            log("Cannot determine serial for auto key exchange")
            _needsKeyExchange.value = true
            _connectionState.value = PumpConnectionState.Ready
            return
        }
        log("Auto key exchange for serial: $serial")

        val keyPair = keyExchange.getOrCreateKeyPair()
        val btAddress = YpsoCrypto.serialToBtAddress(serial)
        val baseUrl = relayUrl.trimEnd('/')

        // Delay before reading pump key (pump needs time after auth, matching Python pairing.py)
        scope.launch {
            delay(500)
            log("Reading pump public key...")
            readPumpKeyWithRetry(5) { data ->
                if (data == null || data.size < 64) {
                    log("Failed to read pump public key (got ${data?.size ?: 0} bytes)")
                    _needsKeyExchange.value = true
                    _connectionState.value = PumpConnectionState.Ready
                    return@readPumpKeyWithRetry
                }

                val challenge = data.copyOfRange(0, 32)
                val pumpPublicKey = data.copyOfRange(32, 64)
                log("Got pump key + challenge (${data.size}B)")

                scope.launch {
                    try {
                        log("Calling relay server...")
                        val response = withContext(Dispatchers.IO) {
                            relayClient.callRelayServer(baseUrl, challenge, pumpPublicKey, keyPair.rawPublicKey, btAddress, keyExchange.deviceId)
                        }
                        log("Relay response received (${response.encryptedBytes.size}B)")

                        // Re-authenticate before writing (relay call takes time, auth may have expired)
                        bleManager.authenticate { authOk ->
                            if (!authOk) {
                                log("Re-auth before write failed")
                                _needsKeyExchange.value = true
                                _connectionState.value = PumpConnectionState.Ready
                                return@authenticate
                            }

                            log("Writing challenge response to pump...")
                            bleManager.writeMultiFrame(YpsoPumpUuids.CHAR_CMD_WRITE, response.encryptedBytes) { writeOk ->
                                if (!writeOk) {
                                    log("Failed to write challenge response")
                                    _needsKeyExchange.value = true
                                    _connectionState.value = PumpConnectionState.Ready
                                    return@writeMultiFrame
                                }

                                log("Challenge response written, deriving shared key...")
                                try {
                                    val sharedKey = YpsoCrypto.deriveSharedKey(keyPair.privateKey, pumpPublicKey)
                                    val cryptor = PumpCryptor.create(context, sharedKey)
                                    bleManager.pumpCryptor = cryptor
                                    _needsKeyExchange.value = false
                                    log("Shared key derived, verifying with status read...")

                                    bleManager.getSystemStatus { status ->
                                        if (status != null) {
                                            onKeyValidated(status)
                                        } else {
                                            log("Key validation failed — new key rejected by pump")
                                            bleManager.pumpCryptor = null
                                            _needsKeyExchange.value = true
                                            _connectionState.value = PumpConnectionState.Ready
                                        }
                                    }
                                } catch (e: Exception) {
                                    log("Key derivation failed: ${e.message}")
                                    _needsKeyExchange.value = true
                                    _connectionState.value = PumpConnectionState.Ready
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log("Relay call failed: ${e.message}")
                        _needsKeyExchange.value = true
                        _connectionState.value = PumpConnectionState.Ready
                    }
                }
            }
        }
    }

    // ==================== SUSPENDING KEY EXCHANGE ====================

    /**
     * Realiza el intercambio automático de claves BLE con la bomba vía servidor relay.
     *
     * Este método se ejecuta en modo suspendido (dentro de una corrutina) y gestiona
     * completamente el proceso de renovación de clave. Secuencia de pasos:
     * 1. Conecta directamente a la bomba con [connectDirect].
     * 2. Autentica con [suspendAuth].
     * 3. Lee la clave pública y el challenge de la bomba desde [YpsoPumpUuids.CHAR_CMD_READ_A]
     *    (64 bytes: 32B challenge + 32B clave pública).
     * 4. Llama al servidor relay (Proregia) vía [RelayClient.callRelayServer] con el challenge,
     *    la clave pública de la bomba, la clave pública del cliente y la dirección BT derivada
     *    del número de serie.
     * 5. Re-autentica (la llamada al relay puede tardar y la bomba puede haber cerrado la sesión).
     * 6. Escribe la respuesta cifrada del relay en [YpsoPumpUuids.CHAR_CMD_WRITE].
     * 7. Deriva la clave compartida: HChaCha20(X25519(privKey, pumpPubKey), nonce_cero_16B).
     * 8. Verifica la nueva clave leyendo el estado del sistema.
     * 9. Desconecta en el bloque `finally`.
     *
     * @return `true` si el intercambio fue exitoso y la nueva clave fue verificada,
     *         `false` en cualquier caso de fallo (URL relay ausente, error de red, rechazo del pump, etc.).
     */
    private suspend fun suspendAutoKeyExchange(): Boolean {
        val savedUrl = loadRelayUrl()
        if (savedUrl.isNullOrBlank()) {
            log("No relay URL saved — cannot auto-renew key")
            _needsKeyExchange.value = true
            return false
        }

        val serial = extractSerial()
        if (serial == null) {
            log("Cannot determine serial for auto key exchange")
            _needsKeyExchange.value = true
            return false
        }

        val mac = loadDeviceMac() ?: return false
        val baseUrl = savedUrl.trimEnd('/')
        val keyPair = keyExchange.getOrCreateKeyPair()
        val btAddress = YpsoCrypto.serialToBtAddress(serial)

        log("Auto key renewal: connecting...")
        val connected = connectDirect(mac)
        if (!connected) {
            log("Auto key renewal: connect failed")
            return false
        }

        try {
            val authOk = suspendAuth()
            if (!authOk) {
                log("Auto key renewal: auth failed")
                return false
            }

            delay(500)

            log("Auto key renewal: reading pump public key...")
            val data = suspendReadPumpKey(5)
            if (data == null || data.size < 64) {
                log("Auto key renewal: failed to read pump key (got ${data?.size ?: 0} bytes)")
                _needsKeyExchange.value = true
                return false
            }

            val challenge = data.copyOfRange(0, 32)
            val pumpPublicKey = data.copyOfRange(32, 64)
            log("Auto key renewal: got pump key + challenge")

            log("Auto key renewal: calling relay server...")
            val response = withContext(Dispatchers.IO) {
                relayClient.callRelayServer(baseUrl, challenge, pumpPublicKey, keyPair.rawPublicKey, btAddress, keyExchange.deviceId)
            }
            log("Auto key renewal: relay response received (${response.encryptedBytes.size}B)")

            // Re-authenticate (relay call takes time, pump may drop auth)
            val reAuthOk = suspendAuth()
            if (!reAuthOk) {
                log("Auto key renewal: re-auth failed")
                _needsKeyExchange.value = true
                return false
            }
            delay(500) // pump needs time after auth before accepting writes

            log("Auto key renewal: writing challenge response...")
            val writeOk = suspendCancellableCoroutine<Boolean> { cont ->
                bleManager.writeMultiFrame(YpsoPumpUuids.CHAR_CMD_WRITE, response.encryptedBytes) { cont.resume(it) }
            }
            if (!writeOk) {
                // Write failed — pump may have rejected stale challenge.
                // Don't retry same payload; let outer withCriticalRetry do a fresh exchange.
                log("Auto key renewal: write failed (pump may have rejected challenge)")
                _needsKeyExchange.value = true
                return false
            }

            val sharedKey = YpsoCrypto.deriveSharedKey(keyPair.privateKey, pumpPublicKey)
            val cryptor = PumpCryptor.create(context, sharedKey)
            bleManager.pumpCryptor = cryptor
            bleManager.resetCounterSync()

            val status = suspendGetSystemStatus()
            if (status == null) {
                log("Auto key renewal: key validation failed")
                bleManager.pumpCryptor = null
                _needsKeyExchange.value = true
                return false
            }

            _needsKeyExchange.value = false
            log("Auto key renewal: SUCCESS")
            return true
        } finally {
            suspendDisconnect()
        }
    }

    private suspend fun suspendReadPumpKey(maxRetries: Int): ByteArray? {
        for (attempt in 1..maxRetries) {
            val data = suspendCancellableCoroutine<ByteArray?> { cont ->
                bleManager.readExtended(YpsoPumpUuids.CHAR_CMD_READ_A) { cont.resume(it) }
            }
            if (data != null && data.size >= 64) return data
            if (attempt < maxRetries) delay(1000)
        }
        return null
    }

    // ==================== STATUS ====================

    /**
     * Obtiene el estado completo y actualizado de la bomba.
     *
     * Realiza una conexión on-demand completa (con renovación de clave si es necesario)
     * y devuelve el [PumpStatus] actualizado por [withPumpConnection] al leer el estado del sistema.
     *
     * @return El [PumpStatus] más reciente de la bomba.
     */
    override suspend fun getFullStatus(): PumpStatus {
        return withPumpConnectionAndKeyRetry { _ ->
            // Status already read and updated by withPumpConnection
            _pumpStatus.value!!
        }
    }

    // ==================== BOLUS ====================

    /**
     * Administra un bolo estándar (inmediato) de [amount] unidades.
     *
     * Usa [withCriticalRetry] + [withPumpConnectionAndKeyRetry] para garantizar fiabilidad.
     * Tras enviar el comando, espera la notificación BLE de finalización del bolo rápido
     * con un timeout de 5 minutos. Si el bolo es cancelado o falla, intenta leer el estado
     * de bolo para obtener la cantidad efectivamente administrada.
     *
     * @param amount Cantidad de insulina en unidades (máximo 25.0 U).
     * @return [BolusDeliveryResult] con el resultado, la cantidad entregada y cualquier mensaje de error.
     */
    override suspend fun deliverStandardBolus(amount: Double): BolusDeliveryResult {
        return try {
            withCriticalRetry("deliverBolus(${"%.2f".format(amount)}U)") {
                withPumpConnectionAndKeyRetry { _ ->
                    val accepted = suspendCancellableCoroutine<Boolean> { cont ->
                        bleManager.startBolus(amount.toFloat(), 0, 0f) { cont.resume(it) }
                    }
                    if (!accepted) {
                        return@withPumpConnectionAndKeyRetry BolusDeliveryResult(false, errorMessage = "Bolus command rejected")
                    }
                    log("Bolus ${"%.2f".format(amount)}U accepted, waiting for completion...")
                    waitForBolusNotification(watchFast = true, watchSlow = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Bolus failed after all retries: ${e.message}")
            BolusDeliveryResult(false, errorMessage = "Bolus failed: ${e.message}")
        }
    }

    /**
     * Administra un bolo extendido de [amount] unidades repartido a lo largo de [durationMinutes].
     *
     * Similar a [deliverStandardBolus] pero usa bolusType=2 y espera la notificación del
     * bloque lento (slow). Usa [withCriticalRetry] para reintentar ante fallos transitorios.
     *
     * @param amount Cantidad total de insulina en unidades.
     * @param durationMinutes Duración del bolo extendido en minutos.
     * @return [BolusDeliveryResult] con el resultado de la operación.
     */
    override suspend fun deliverExtendedBolus(amount: Double, durationMinutes: Int): BolusDeliveryResult {
        return try {
            withCriticalRetry("deliverExtendedBolus(${"%.2f".format(amount)}U/${durationMinutes}min)") {
                withPumpConnectionAndKeyRetry { _ ->
                    val accepted = suspendCancellableCoroutine<Boolean> { cont ->
                        bleManager.startBolus(amount.toFloat(), durationMinutes, 0f) { cont.resume(it) }
                    }
                    if (!accepted) {
                        return@withPumpConnectionAndKeyRetry BolusDeliveryResult(false, errorMessage = "Extended bolus rejected")
                    }
                    log("Extended bolus ${"%.2f".format(amount)}U/${durationMinutes}min accepted, waiting...")
                    waitForBolusNotification(watchFast = false, watchSlow = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Extended bolus failed after all retries: ${e.message}")
            BolusDeliveryResult(false, errorMessage = "Extended bolus failed: ${e.message}")
        }
    }

    /**
     * Administra un bolo multionda: una parte inmediata ([immediateAmount]) y una parte extendida
     * ([extendedAmount]) repartida durante [durationMinutes].
     *
     * El total (inmediato + extendido) se envía como `totalUnits`, con `immediateUnits` indicando
     * la fracción rápida. Espera notificaciones de finalización de ambos bloques (rápido y lento).
     *
     * @param immediateAmount Cantidad inmediata en unidades.
     * @param extendedAmount Cantidad extendida en unidades.
     * @param durationMinutes Duración de la parte extendida en minutos.
     * @return [BolusDeliveryResult] con el resultado de la operación.
     */
    override suspend fun deliverMultiwaveBolus(
        immediateAmount: Double,
        extendedAmount: Double,
        durationMinutes: Int
    ): BolusDeliveryResult {
        return try {
            val total = immediateAmount + extendedAmount
            withCriticalRetry("deliverMultiwaveBolus(${"%.2f".format(total)}U/${durationMinutes}min)") {
                withPumpConnectionAndKeyRetry { _ ->
                    val accepted = suspendCancellableCoroutine<Boolean> { cont ->
                        bleManager.startBolus(total.toFloat(), durationMinutes, immediateAmount.toFloat()) { cont.resume(it) }
                    }
                    if (!accepted) {
                        return@withPumpConnectionAndKeyRetry BolusDeliveryResult(false, errorMessage = "Multiwave bolus rejected")
                    }
                    log("Multiwave bolus ${"%.2f".format(total)}U accepted, waiting...")
                    waitForBolusNotification(watchFast = true, watchSlow = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Multiwave bolus failed after all retries: ${e.message}")
            BolusDeliveryResult(false, errorMessage = "Multiwave bolus failed: ${e.message}")
        }
    }

    /**
     * Stays connected and listens for CHAR_BOLUS_NOTIFICATION until the bolus finishes.
     * After terminal notification, reads CHAR_BOLUS_STATUS to get actual delivered amount.
     * Returns the final result (completed/cancelled/error/timeout).
     */
    private suspend fun waitForBolusNotification(
        watchFast: Boolean,
        watchSlow: Boolean,
        timeoutMs: Long = 300_000L  // 5 minutes max
    ): BolusDeliveryResult {
        var fastDone = !watchFast   // if not watching, consider it done
        var slowDone = !watchSlow
        var errorMsg: String? = null

        val notifResult = withTimeoutOrNull(timeoutMs) {
            bleManager.dataReceived
                .mapNotNull { event ->
                    if (event !is DataEvent.ProBluetoothNotification) return@mapNotNull null
                    if (event.characteristic != "BolusNotification") return@mapNotNull null
                    val notif = BolusNotification.parse(event.data) ?: return@mapNotNull null

                    log("Bolus notification: fast=${BolusNotificationStatus.name(notif.fastStatus)} slow=${BolusNotificationStatus.name(notif.slowStatus)}")

                    if (watchFast && BolusNotificationStatus.isTerminal(notif.fastStatus)) {
                        fastDone = true
                        if (notif.fastStatus != BolusNotificationStatus.COMPLETED) {
                            errorMsg = "Fast bolus ${BolusNotificationStatus.name(notif.fastStatus).lowercase()}"
                        }
                    }
                    if (watchSlow && BolusNotificationStatus.isTerminal(notif.slowStatus)) {
                        slowDone = true
                        if (notif.slowStatus != BolusNotificationStatus.COMPLETED) {
                            errorMsg = errorMsg ?: "Extended bolus ${BolusNotificationStatus.name(notif.slowStatus).lowercase()}"
                        }
                    }

                    if (fastDone && slowDone) true else null  // signal: both done
                }
                .first()
        }

        if (notifResult == null) {
            log("Bolus notification timeout after ${timeoutMs / 1000}s")
            return BolusDeliveryResult(false, errorMessage = "Bolus timeout — check pump")
        }

        // For cancelled boluses, read CHAR_BOLUS_STATUS for actual partial delivery amount.
        // For completed boluses, the pump resets status to idle (zeroed fields) before we can
        // read it, so we don't attempt — the caller uses the requested amount as delivered.
        var delivered: Float? = null
        var requested: Float? = null

        if (errorMsg != null) {
            // Cancelled or error — try to read actual injected amount
            val bolusStatus = suspendGetBolusStatus()
            if (bolusStatus != null) {
                delivered = if (watchFast && watchSlow) {
                    bolusStatus.fastInjected + bolusStatus.slowInjected
                } else if (watchFast) {
                    bolusStatus.fastInjected
                } else {
                    bolusStatus.slowInjected
                }
                requested = if (watchFast && watchSlow) {
                    bolusStatus.fastTotal + bolusStatus.slowTotal
                } else if (watchFast) {
                    bolusStatus.fastTotal
                } else {
                    bolusStatus.slowTotal
                }
                log("Bolus status after cancel: delivered=${"%.2f".format(delivered)}U, requested=${"%.2f".format(requested)}U")
            } else {
                log("Failed to read bolus status after cancel — delivered amount unknown")
            }
        }

        val result = BolusDeliveryResult(
            success = errorMsg == null,
            errorMessage = errorMsg,
            deliveredUnits = delivered,
            requestedUnits = requested
        )
        log("Bolus finished: success=${result.success}" +
            (delivered?.let { ", delivered=${"%.2f".format(it)}U" } ?: "") +
            (result.errorMessage?.let { ", msg=$it" } ?: ""))
        return result
    }

    /**
     * Cancela el bolo activo en la bomba.
     *
     * Envía un payload de cancelación con bolusType=1 (fast) a la característica de bolo.
     * Usa [withCriticalRetry] para garantizar que el comando llega a la bomba.
     *
     * @param bolusId No utilizado en YpsoPump; la bomba solo admite un bolo activo a la vez.
     * @return `true` si el comando de cancelación fue aceptado.
     */
    override suspend fun cancelBolus(bolusId: Int?): Boolean {
        return try {
            withCriticalRetry("cancelBolus") {
                withPumpConnectionAndKeyRetry { _ ->
                    suspendCancellableCoroutine { cont ->
                        bleManager.cancelBolus("fast") { cont.resume(it) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Cancel bolus failed after all retries: ${e.message}")
            false
        }
    }

    // ==================== TBR ====================

    /**
     * Establece una tasa basal temporal (TBR) en la bomba.
     *
     * Envía el porcentaje y la duración en formato GLB a la característica TBR.
     * El porcentaje es absoluto (p. ej. 50 para 50%, 150 para 150%). Usa [withCriticalRetry]
     * para reintentar ante fallos transitorios.
     *
     * @param percentage Porcentaje de la tasa basal temporal (rango: 0-200).
     * @param durationMinutes Duración en minutos.
     * @return `true` si el comando fue aceptado por la bomba.
     */
    override suspend fun setTbr(percentage: Int, durationMinutes: Int): Boolean {
        return try {
            withCriticalRetry("setTbr($percentage%, ${durationMinutes}min)") {
                withPumpConnectionAndKeyRetry { _ ->
                    suspendCancellableCoroutine { cont ->
                        bleManager.startTbr(percentage, durationMinutes) { cont.resume(it) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("TBR failed after all retries: ${e.message}")
            false
        }
    }

    /**
     * Cancela la tasa basal temporal (TBR) activa y restablece la tasa basal normal (100%).
     *
     * Envía el comando GLB(100) + GLB(0) a la característica TBR. Usa [withCriticalRetry].
     *
     * @return `true` si el comando fue aceptado por la bomba.
     */
    override suspend fun cancelTbr(): Boolean {
        return try {
            withCriticalRetry("cancelTbr") {
                withPumpConnectionAndKeyRetry { _ ->
                    suspendCancellableCoroutine { cont ->
                        bleManager.cancelTbr { cont.resume(it) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Cancel TBR failed after all retries: ${e.message}")
            false
        }
    }

    // ==================== DATE/TIME ====================

    /**
     * Sincroniza la fecha y la hora del sistema Android con la bomba.
     *
     * Escribe primero la fecha cifrada y luego la hora cifrada en las características
     * correspondientes. Usa [withCriticalRetry] para garantizar que la sincronización llega
     * a la bomba incluso ante fallos transitorios de BLE.
     *
     * @return `true` si tanto la fecha como la hora se escribieron correctamente.
     */
    override suspend fun syncDateTime(): Boolean {
        return try {
            withCriticalRetry("syncDateTime") {
                withPumpConnectionAndKeyRetry { _ ->
                    suspendCancellableCoroutine { cont ->
                        bleManager.syncTime { cont.resume(it) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("syncDateTime failed after all retries: ${e.message}")
            false
        }
    }

    // ==================== BASAL PROFILES ====================

    /**
     * Lee el perfil basal del programa indicado.
     *
     * Mapea [profileId] al programa de la bomba ('A' para 0, 'B' para 1) y lee los 24
     * ajustes horarios correspondientes. Devuelve un [BasalProfile] con bloques de 60 minutos.
     *
     * @param profileId Índice del perfil: 0 para programa A, 1 para programa B.
     * @return [BasalProfile] con 24 bloques de 60 minutos, o `null` si la lectura falló.
     */
    override suspend fun readBasalProfile(profileId: Int): BasalProfile? {
        return withPumpConnectionAndKeyRetry { _ ->
            val program = if (profileId == 0) 'A' else 'B'
            val rates = suspendCancellableCoroutine<List<Float>?> { cont ->
                bleManager.readBasalProfile(program) { cont.resume(it) }
            } ?: return@withPumpConnectionAndKeyRetry null

            BasalProfile(
                profileId = profileId,
                name = "Profile $program",
                blocks = rates.mapIndexed { i, rate ->
                    BasalBlock(durationMinutes = 60, basalRate = rate.toDouble())
                }
            )
        }
    }

    /**
     * Escribe un perfil basal completo en la bomba.
     *
     * Itera por todos los bloques del perfil y escribe cada tasa (en centi-unidades/hora)
     * en el índice de ajuste correspondiente. Si alguna escritura falla, devuelve `false`
     * inmediatamente sin escribir los ajustes restantes.
     *
     * @param profile El [BasalProfile] a escribir (profileId 0 = programa A, 1 = programa B).
     * @return `true` si todos los ajustes se escribieron correctamente.
     */
    override suspend fun writeBasalProfile(profile: BasalProfile): Boolean {
        return withPumpConnectionAndKeyRetry { _ ->
            val program = if (profile.profileId == 0) 'A' else 'B'
            val startIndex = if (program == 'A') SettingsIndex.PROGRAM_A_START else SettingsIndex.PROGRAM_B_START
            val rates = profile.blocks.map { (it.basalRate * 100).toInt() }

            for (i in rates.indices) {
                val success = suspendCancellableCoroutine<Boolean> { cont ->
                    bleManager.writeSetting(startIndex + i, rates[i]) { cont.resume(it) }
                }
                if (!success) return@withPumpConnectionAndKeyRetry false
            }
            true
        }
    }

    /**
     * Obtiene el identificador del perfil basal activo en la bomba.
     *
     * @return 0 si el programa activo es 'A', 1 si es 'B', o `null` si no se pudo leer.
     */
    override suspend fun getActiveProfileId(): Int? {
        return withPumpConnectionAndKeyRetry { _ ->
            val program = suspendCancellableCoroutine<Char?> { cont ->
                bleManager.readActiveProgram { cont.resume(it) }
            }
            when (program) {
                'A' -> 0
                'B' -> 1
                else -> null
            }
        }
    }

    /**
     * Activa el perfil basal indicado en la bomba.
     *
     * @param profileId 0 para activar el programa A, 1 para el programa B.
     * @return `true` si el ajuste se escribió correctamente.
     */
    override suspend fun setActiveProfile(profileId: Int): Boolean {
        return withPumpConnectionAndKeyRetry { _ ->
            val value = if (profileId == 0) SettingsIndex.PROGRAM_A_VALUE else SettingsIndex.PROGRAM_B_VALUE
            suspendCancellableCoroutine { cont ->
                bleManager.writeSetting(SettingsIndex.ACTIVE_PROGRAM, value) { cont.resume(it) }
            }
        }
    }

    // ==================== HISTORY ====================

    override suspend fun readHistory(sincePosition: Long, maxEvents: Int): List<PumpHistoryEvent> {
        // History reading via the PumpManager interface returns common history events
        // For now, return empty - full history mapping can be added later
        return emptyList()
    }

    // ==================== CONFIG ====================

    override fun configure(key: String, value: Any) {
        when (key) {
            PumpManager.CONFIG_RELAY_URL -> {
                val url = value as String
                saveRelayUrl(url.trimEnd('/'))
            }
            PumpManager.CONFIG_SHARED_KEY -> {
                val hex = (value as String).trim().replace(" ", "").replace(":", "")
                if (hex.length == 64) {
                    val keyBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val cryptor = PumpCryptor.create(context, keyBytes)
                    bleManager.pumpCryptor = cryptor
                    _needsKeyExchange.value = false
                }
            }
        }
    }

    // ==================== RELAY KEY EXCHANGE (public for service) ====================

    /**
     * Inicia manualmente un intercambio de claves con la bomba vía servidor relay.
     *
     * Guarda la URL del relay, autentica con la bomba conectada actualmente, lee el challenge
     * y la clave pública de la bomba, llama al relay y escribe la respuesta. Si todo tiene éxito,
     * deriva la clave compartida y la verifica con una lectura de estado del sistema.
     *
     * A diferencia de [suspendAutoKeyExchange], este método opera sobre la conexión BLE ya activa
     * (no realiza una nueva conexión) y usa callbacks en lugar de corrutinas.
     *
     * @param relayUrl URL base del servidor relay (Proregia). Se elimina la barra final si la hay.
     */
    fun performKeyExchangeViaRelay(relayUrl: String) {
        val serial = extractSerial()
        if (serial == null) {
            log("Cannot determine serial")
            return
        }

        val baseUrl = relayUrl.trimEnd('/')
        saveRelayUrl(baseUrl)

        val keyPair = keyExchange.getOrCreateKeyPair()
        val btAddress = YpsoCrypto.serialToBtAddress(serial)

        bleManager.authenticate { authOk ->
            if (!authOk) return@authenticate

            readPumpKeyWithRetry(5) { data ->
                if (data == null || data.size < 64) return@readPumpKeyWithRetry

                val challenge = data.copyOfRange(0, 32)
                val pumpPublicKey = data.copyOfRange(32, 64)

                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            relayClient.callRelayServer(baseUrl, challenge, pumpPublicKey, keyPair.rawPublicKey, btAddress, keyExchange.deviceId)
                        }

                        bleManager.authenticate { authOk2 ->
                            if (!authOk2) return@authenticate

                            bleManager.writeMultiFrame(YpsoPumpUuids.CHAR_CMD_WRITE, response.encryptedBytes) { writeOk ->
                                if (!writeOk) return@writeMultiFrame

                                try {
                                    val sharedKey = YpsoCrypto.deriveSharedKey(keyPair.privateKey, pumpPublicKey)
                                    val cryptor = PumpCryptor.create(context, sharedKey)
                                    bleManager.pumpCryptor = cryptor
                                    _needsKeyExchange.value = false

                                    bleManager.getSystemStatus { status ->
                                        if (status != null) {
                                            updateStatusFromSystem(status)
                                            log("Key exchange via relay successful")
                                        }
                                    }
                                } catch (e: Exception) {
                                    log("Key derivation failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log("Relay call failed: ${e.message}")
                    }
                }
            }
        }
    }

    // ==================== ADDITIONAL PUBLIC METHODS (for service) ====================

    /**
     * Solicita una actualización del estado de la bomba de forma asíncrona (fire-and-forget).
     *
     * Lanza una corrutina que ejecuta [withPumpConnectionAndKeyRetry]. El estado actualizado
     * queda disponible en el flujo [pumpStatus]. Los errores se emiten en [userMessages].
     */
    fun requestStatus() {
        scope.launch {
            try {
                withPumpConnectionAndKeyRetry { _ ->
                    // Status already read by withPumpConnection
                }
            } catch (e: Exception) {
                log("Status request failed: ${e.message}")
                _userMessages.emit("Status: ${e.message}")
            }
        }
    }

    /**
     * Actualiza el estado completo de la bomba, incluyendo el programa basal activo.
     *
     * Realiza una conexión on-demand y lee el programa activo además del estado del sistema.
     * Útil para refrescar la UI tras cambios en la configuración de la bomba.
     */
    fun refreshFullStatus() {
        scope.launch {
            try {
                withPumpConnectionAndKeyRetry { _ ->
                    val program = suspendCancellableCoroutine<Char?> { cont ->
                        bleManager.readActiveProgram { cont.resume(it) }
                    }
                    if (program != null) _activeProfile.value = program
                }
            } catch (e: Exception) {
                log("Refresh failed: ${e.message}")
                _userMessages.emit("Refresh: ${e.message}")
            }
        }
    }

    /**
     * Administra un bolo estándar de forma asíncrona (fire-and-forget) para uso desde el servicio.
     *
     * Envuelve [deliverStandardBolus] en una corrutina y emite el resultado como [PumpEvent]
     * ([PumpEvent.BolusCompleted], [PumpEvent.BolusCancelled] o [PumpEvent.BolusError]) y
     * un mensaje de usuario en [userMessages].
     *
     * @param units Cantidad de insulina en unidades.
     */
    fun startBolus(units: Float) {
        scope.launch {
            try {
                val result = deliverStandardBolus(units.toDouble())
                val delivered = result.deliveredUnits ?: units
                val requested = result.requestedUnits ?: units
                if (result.success) {
                    _userMessages.emit("Bolus ${delivered}U completed")
                    emitEvent(PumpEvent.BolusCompleted(delivered = delivered, requested = requested))
                } else if (result.deliveredUnits != null && delivered > 0f) {
                    _userMessages.emit("Bolus cancelled: ${delivered}U of ${requested}U delivered")
                    emitEvent(PumpEvent.BolusCancelled(delivered = delivered, requested = requested))
                } else {
                    _userMessages.emit("Bolus failed: ${result.errorMessage}")
                    emitEvent(PumpEvent.BolusError(result.errorMessage ?: "Bolus failed"))
                }
            } catch (e: Exception) {
                log("Bolus failed: ${e.message}")
                _userMessages.emit("Bolus failed: ${e.message}")
                emitEvent(PumpEvent.BolusError(e.message ?: "Bolus failed"))
            }
        }
    }

    /**
     * Cancela el bolo activo de forma asíncrona (fire-and-forget) para uso desde el servicio.
     *
     * Envuelve [cancelBolus] en una corrutina. Los errores se emiten en [userMessages].
     */
    fun cancelBolusSync() {
        scope.launch {
            try {
                cancelBolus()
            } catch (e: Exception) {
                log("Cancel bolus failed: ${e.message}")
                _userMessages.emit("Cancel bolus: ${e.message}")
            }
        }
    }

    /**
     * Establece una tasa basal temporal de forma asíncrona (fire-and-forget) para uso desde el servicio.
     *
     * Envuelve [setTbr] en una corrutina. Los errores se emiten en [userMessages].
     *
     * @param percent Porcentaje de la TBR (p. ej. 50 para 50%).
     * @param durationMinutes Duración de la TBR en minutos.
     */
    fun setTbrSync(percent: Int, durationMinutes: Int) {
        scope.launch {
            try {
                setTbr(percent, durationMinutes)
            } catch (e: Exception) {
                log("TBR failed: ${e.message}")
                _userMessages.emit("TBR failed: ${e.message}")
            }
        }
    }

    /**
     * Cancela la tasa basal temporal activa de forma asíncrona (fire-and-forget).
     *
     * Envuelve [cancelTbr] en una corrutina. Los errores se emiten en [userMessages].
     */
    fun cancelTbrSync() {
        scope.launch {
            try {
                cancelTbr()
            } catch (e: Exception) {
                log("Cancel TBR failed: ${e.message}")
                _userMessages.emit("Cancel TBR: ${e.message}")
            }
        }
    }

    /**
     * Sincroniza la fecha y hora con la bomba de forma asíncrona (fire-and-forget).
     *
     * Envuelve [syncDateTime] en una corrutina. Los errores se emiten en [userMessages].
     */
    fun syncTimeSync() {
        scope.launch {
            try {
                syncDateTime()
            } catch (e: Exception) {
                log("Sync time failed: ${e.message}")
                _userMessages.emit("Sync time: ${e.message}")
            }
        }
    }

    /**
     * Lee el perfil basal del programa indicado de forma asíncrona (fire-and-forget).
     *
     * Si la lectura tiene éxito, actualiza [profileRates] con las 24 tasas horarias en U/h.
     * Los errores se emiten en [userMessages].
     *
     * @param program Identificador del programa: 'A' o 'B'.
     */
    fun readBasalProfileSync(program: Char) {
        scope.launch {
            try {
                withPumpConnectionAndKeyRetry { _ ->
                    val rates = suspendCancellableCoroutine<List<Float>?> { cont ->
                        bleManager.readBasalProfile(program) { cont.resume(it) }
                    }
                    if (rates != null) _profileRates.value = rates
                }
            } catch (e: Exception) {
                log("Read basal profile failed: ${e.message}")
                _userMessages.emit("Read profile: ${e.message}")
            }
        }
    }

    /**
     * Lee el programa basal activo de forma asíncrona (fire-and-forget).
     *
     * Si la lectura tiene éxito, actualiza [activeProfile] con 'A' o 'B'.
     * Los errores se emiten en [userMessages].
     */
    fun readActiveProgramSync() {
        scope.launch {
            try {
                withPumpConnectionAndKeyRetry { _ ->
                    val program = suspendCancellableCoroutine<Char?> { cont ->
                        bleManager.readActiveProgram { cont.resume(it) }
                    }
                    if (program != null) _activeProfile.value = program
                }
            } catch (e: Exception) {
                log("Read active program failed: ${e.message}")
                _userMessages.emit("Read program: ${e.message}")
            }
        }
    }

    /**
     * Escribe un perfil basal completo en la bomba de forma asíncrona (fire-and-forget).
     *
     * Convierte cada tasa en centi-unidades/hora y la escribe en el índice de ajuste
     * correspondiente al programa indicado. Si alguna escritura falla, se aborta y se
     * emite un mensaje de error en [userMessages].
     *
     * @param program Identificador del programa: 'A' o 'B'.
     * @param rates Lista de 24 tasas basales en U/h (una por hora del día).
     */
    fun writeBasalProfileSync(program: Char, rates: List<Float>) {
        scope.launch {
            try {
                withPumpConnectionAndKeyRetry { _ ->
                    val startIndex = if (program == 'A') SettingsIndex.PROGRAM_A_START else SettingsIndex.PROGRAM_B_START
                    val scaled = rates.map { (it * 100).toInt() }
                    for (i in scaled.indices) {
                        val success = suspendCancellableCoroutine<Boolean> { cont ->
                            bleManager.writeSetting(startIndex + i, scaled[i]) { cont.resume(it) }
                        }
                        if (!success) {
                            log("Write basal rate $i failed")
                            _userMessages.emit("Write basal rate $i failed")
                            return@withPumpConnectionAndKeyRetry
                        }
                    }
                }
            } catch (e: Exception) {
                log("Write basal profile failed: ${e.message}")
                _userMessages.emit("Write profile: ${e.message}")
            }
        }
    }

    /**
     * Activa el programa basal indicado en la bomba de forma asíncrona (fire-and-forget).
     *
     * Si la escritura tiene éxito, actualiza [activeProfile] con el programa activado.
     *
     * @param program Identificador del programa a activar: 'A' o 'B'.
     */
    fun activateProfile(program: Char) {
        scope.launch {
            try {
                withPumpConnectionAndKeyRetry { _ ->
                    val value = if (program == 'A') SettingsIndex.PROGRAM_A_VALUE else SettingsIndex.PROGRAM_B_VALUE
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        bleManager.writeSetting(SettingsIndex.ACTIVE_PROGRAM, value) { cont.resume(it) }
                    }
                    if (success) _activeProfile.value = program
                }
            } catch (e: Exception) {
                log("Activate profile failed: ${e.message}")
                _userMessages.emit("Activate profile: ${e.message}")
            }
        }
    }

    /** Devuelve `true` si hay una clave compartida válida almacenada en caché. */
    fun hasEncryption(): Boolean = keyExchange.hasValidSharedKey()

    /**
     * Elimina la clave de cifrado almacenada en caché y fuerza un nuevo intercambio de claves.
     *
     * Limpia las preferencias de criptografía, anula el criptador activo en [YpsoBleManager]
     * y activa [needsKeyExchange] para notificar a la UI que se requiere renovación.
     */
    fun clearEncryptionKey() {
        clearCachedKey()
        bleManager.pumpCryptor = null
        _needsKeyExchange.value = true
    }

    /**
     * Establece manualmente la clave compartida a partir de una cadena hexadecimal de 64 caracteres.
     *
     * Convierte la cadena hex a bytes, crea un [PumpCryptor] y lo instala en [YpsoBleManager].
     * Si la cadena no tiene exactamente 64 caracteres hexadecimales, no hace nada.
     *
     * @param hex Clave de 32 bytes en formato hexadecimal (64 caracteres, con o sin separadores).
     */
    fun setSharedKeyManually(hex: String) {
        val clean = hex.trim().replace(" ", "").replace(":", "")
        if (clean.length != 64) return
        val keyBytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cryptor = PumpCryptor.create(context, keyBytes)
        bleManager.pumpCryptor = cryptor
        _needsKeyExchange.value = false
    }

    /**
     * Libera todos los recursos del SDK: detiene el sondeo, cancela el escaneo, cancela
     * la corrutina raíz, desregistra el receptor de bonding y cierra [YpsoBleManager].
     *
     * Debe llamarse cuando el componente Android que mantiene esta instancia (Service, ViewModel)
     * es destruido para evitar fugas de recursos.
     */
    fun close() {
        pollManager.stop()
        stopScan()
        job.cancel()
        try {
            bondReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        bleManager.close()
    }

    // ==================== POLLING ====================

    private suspend fun pollPumpStatus() {
        if (_connectionState.value !is PumpConnectionState.Ready) return

        try {
            withPumpConnectionAndKeyRetry { status ->
                consecutivePollFailures = 0
                val events = eventProcessor.processSystemStatus(status)
                events.forEach { emitEvent(it) }

                // Check history deltas
                val eventsCount = suspendCancellableCoroutine<Int?> { cont ->
                    bleManager.readHistoryEventsCount { cont.resume(it) }
                }
                if (eventsCount != null) {
                    if (eventProcessor.lastKnownEventsCount >= 0 && eventsCount > eventProcessor.lastKnownEventsCount) {
                        val delta = eventsCount - eventProcessor.lastKnownEventsCount
                        log("$delta new event(s) detected")
                        val entries = suspendCancellableCoroutine<List<HistoryEntry>> { cont ->
                            bleManager.readHistoryEvents(delta) { cont.resume(it) }
                        }
                        val pumpEvents = eventProcessor.processHistoryEntries(entries)
                        pumpEvents.forEach { emitEvent(it) }
                    }
                    eventProcessor.lastKnownEventsCount = eventsCount
                }

                val alertsCount = suspendCancellableCoroutine<Int?> { cont ->
                    bleManager.readHistoryAlertsCount { cont.resume(it) }
                }
                if (alertsCount != null) {
                    if (eventProcessor.lastKnownAlertsCount >= 0 && alertsCount > eventProcessor.lastKnownAlertsCount) {
                        val delta = alertsCount - eventProcessor.lastKnownAlertsCount
                        log("$delta new alert(s) detected")
                        val entries = suspendCancellableCoroutine<List<HistoryEntry>> { cont ->
                            bleManager.readHistoryAlerts(delta) { cont.resume(it) }
                        }
                        val pumpEvents = eventProcessor.processHistoryEntries(entries)
                        pumpEvents.forEach { emitEvent(it) }
                    }
                    eventProcessor.lastKnownAlertsCount = alertsCount
                }
            }
        } catch (e: Exception) {
            log("Poll failed: ${e.message}")
            consecutivePollFailures++
            if (consecutivePollFailures >= 3) {
                consecutivePollFailures = 0
                log("Too many poll failures, needs manual reconnect")
            }
        }
    }

    // ==================== SUSPEND HELPERS ====================

    private suspend fun suspendAuth(): Boolean =
        suspendCancellableCoroutine { cont ->
            bleManager.authenticate { cont.resume(it) }
        }

    private suspend fun suspendGetSystemStatus(): SystemStatusData? =
        suspendCancellableCoroutine { cont ->
            bleManager.getSystemStatus { cont.resume(it) }
        }

    private suspend fun suspendGetBolusStatus(): BolusStatusData? =
        suspendCancellableCoroutine { cont ->
            bleManager.getBolusStatus { cont.resume(it) }
        }

    // ==================== INTERNAL HELPERS ====================

    private fun updateStatusFromSystem(status: SystemStatusData) {
        _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(
            batteryPercent = status.batteryPercent,
            reservoirUnits = status.insulinRemaining.toDouble(),
            deliveryModeName = status.deliveryModeName,
            isDelivering = status.deliveryMode != DeliveryMode.STOPPED && status.deliveryMode != DeliveryMode.PAUSED
        )
    }

    private fun readPumpInfo() {
        bleManager.readDeviceName { name ->
            if (name != null) _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(serialNumber = name)
        }
        bleManager.readManufacturer { manufacturer ->
            if (manufacturer != null) _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(manufacturer = manufacturer)
        }
        bleManager.readSoftwareRevision { version ->
            if (version != null) _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(firmwareVersion = version)
        }
    }

    private fun readPumpKeyWithRetry(maxRetries: Int, attempt: Int = 1, callback: (ByteArray?) -> Unit) {
        bleManager.readExtended(YpsoPumpUuids.CHAR_CMD_READ_A) { data ->
            if (data != null && data.size >= 64) {
                callback(data)
            } else if (attempt < maxRetries) {
                scope.launch {
                    delay(1000)
                    readPumpKeyWithRetry(maxRetries, attempt + 1, callback)
                }
            } else {
                callback(null)
            }
        }
    }

    private fun extractSerial(): String? {
        val name = connectedDeviceName
            ?: _pumpStatus.value?.serialNumber
            ?: return null
        val serial = name.removePrefix("YpsoPump_")
        return serial.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
    }

    private fun handleDataEvent(event: DataEvent) {
        when (event) {
            is DataEvent.ProBluetoothNotification -> {
                if (event.characteristic == "BolusNotification") {
                    val pumpEvents = eventProcessor.processBolusNotification(event.data)
                    pumpEvents.forEach { emitEvent(it) }
                }
            }
            else -> {}
        }
    }

    private fun saveDeviceMac(mac: String) {
        context.getSharedPreferences("ypso_device", Context.MODE_PRIVATE)
            .edit().putString("device_mac", mac).apply()
    }

    private fun loadDeviceMac(): String? {
        return context.getSharedPreferences("ypso_device", Context.MODE_PRIVATE)
            .getString("device_mac", null)
    }

    private fun saveDeviceName(name: String) {
        context.getSharedPreferences("ypso_device", Context.MODE_PRIVATE)
            .edit().putString("device_name", name).apply()
    }

    private fun loadDeviceName(): String? {
        return context.getSharedPreferences("ypso_device", Context.MODE_PRIVATE)
            .getString("device_name", null)
    }

    private fun clearDeviceCache() {
        context.getSharedPreferences("ypso_device", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun saveRelayUrl(url: String) {
        context.getSharedPreferences("ypso_key_exchange", Context.MODE_PRIVATE)
            .edit().putString("relay_url", url).apply()
    }

    private fun loadRelayUrl(): String? {
        return context.getSharedPreferences("ypso_key_exchange", Context.MODE_PRIVATE)
            .getString("relay_url", null)
    }

    private fun clearCachedKey() {
        context.getSharedPreferences("ypso_crypto", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @SuppressLint("MissingPermission")
    private fun registerBondReceiver() {
        bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                    if (device != null && device.address == pendingBondAddress && bondState == BluetoothDevice.BOND_BONDED) {
                        pendingBondAddress = null
                        val name = device.name ?: "YpsoPump"
                        connectAfterBond(device, name)
                    } else if (bondState == BluetoothDevice.BOND_NONE && device?.address == pendingBondAddress) {
                        pendingBondAddress = null
                        _connectionState.value = PumpConnectionState.Error("Bonding failed")
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bondReceiver, filter)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.tryEmit(message)
    }

    private fun emitEvent(event: PumpEvent) {
        scope.launch { _pumpEvents.emit(event) }
    }
}
