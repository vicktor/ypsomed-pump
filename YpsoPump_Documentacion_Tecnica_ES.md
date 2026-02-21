# Integración YpsoPump — Documentación Técnica Completa

Documentación del protocolo BLE de la bomba de insulina YpsoPump y su implementación
en Android (Kotlin). Basada en ingeniería inversa de la app oficial **mylife YpsoPump**
y el proyecto de referencia [Uneo7/Ypso](https://github.com/Uneo7/Ypso) (Python).

---

## Índice

1. [Arquitectura General](#1-arquitectura-general)
2. [Servicios y Características BLE](#2-servicios-y-características-ble)
3. [Autenticación](#3-autenticación)
4. [Cifrado (XChaCha20-Poly1305)](#4-cifrado-xchacha20-poly1305)
5. [Intercambio de Claves](#5-intercambio-de-claves)
    - [Token de Play Integrity](#token-de-play-integrity)
    - [Validez de la Clave Compartida](#validez-de-la-clave-compartida)
6. [Protocolo de Tramas (Framing)](#6-protocolo-de-tramas-framing)
7. [CRC16](#7-crc16)
8. [Variables Seguras GLB](#8-variables-seguras-glb)
9. [Sincronización de Contadores](#9-sincronización-de-contadores)
10. [Comandos de la Bomba](#10-comandos-de-la-bomba)
    - [Estado del Sistema](#101-estado-del-sistema)
    - [Bolus](#102-bolus)
    - [TBR (Tasa Basal Temporal)](#103-tbr-tasa-basal-temporal)
    - [Perfiles Basales](#104-perfiles-basales)
    - [Sincronización de Hora](#105-sincronización-de-hora)
    - [Historial](#106-historial)
11. [Flujo Completo de Conexión](#11-flujo-completo-de-conexión)
12. [Recuperación Automática de Clave](#12-recuperación-automática-de-clave)
13. [Estructura del Proyecto](#13-estructura-del-proyecto)
14. [Referencia Rápida de Formatos](#14-referencia-rápida-de-formatos)

---

## 1. Arquitectura General

La YpsoPump expone un protocolo BLE propietario llamado **ProBluetooth** con dos servicios GATT principales:

```
┌──────────────┐         BLE GATT        ┌────────────────────┐
│  Android App │ ◄─────────────────────► │    YpsoPump        │
│              │                         │                    │
│  BleManager  │   Service: Security     │  Autenticación     │
│  (Nordic)    │   Service: Write        │  Comandos          │
│              │   Service: DeviceInfo   │  Estado/Historial  │
└──────────────┘                         └────────────────────┘
```

**Pipeline de envío de datos (escritura a la bomba):**

```
Payload → [+CRC16] → [Cifrar] → Fragmentar en tramas → Escribir BLE
```

**Pipeline de recepción de datos (lectura de la bomba):**

```
Leer BLE → Ensamblar tramas → [Descifrar] → [Verificar CRC16] → Payload
```

> Nota: Los corchetes `[]` indican pasos opcionales según el tipo de comando.
> Algunos comandos (como TBR y Settings) usan formato GLB sin CRC.

---

## 2. Servicios y Características BLE

### Servicios

| Servicio | UUID | Descripción |
|----------|------|-------------|
| Security | `fb349b5f-8000-0080-0010-0000feda0000` | Autenticación y seguridad |
| Write | `fb349b5f-8000-0080-0010-0000feda0002` | Comandos y transporte |
| Device Info | `0000180a-0000-1000-8000-00805f9b34fb` | Información estándar BLE |

### Características Principales

Todas las características de la bomba siguen el prefijo `669a0c20-0008-969e-e211-`.

| Función | UUID (sufijo) | Propiedades | Cifrada | Formato |
|---------|--------------|-------------|---------|---------|
| Auth Password | `fcbeb2147bc5` | Write | No | MD5 16B |
| Master Version | `fcbeb0147bc5` | Read | No | String |
| System Date | `fcbedc3b7bc5` | Read/Write | Sí | 4B + CRC |
| System Time | `fcbedd3b7bc5` | Read/Write | Sí | 3B + CRC |
| Bolus Start/Stop | `fcbee18b7bc5` | Write | Sí | 13B + CRC |
| Bolus Status | `fcbee28b7bc5` | Read/Notify | Sí | Variable + CRC |
| TBR Start/Stop | `fcbee38b7bc5` | Write | Sí | 16B GLB (sin CRC) |
| System Status | `fcbee48b7bc5` | Read/Notify | Sí | 6B + CRC |
| Bolus Notification | `fcbee58b7bc5` | Notify | No | 13B |
| Security Status | `fcbee08b7bc5` | Read | Sí | Variable |
| Setting ID | `fcbeb3147bc5` | Write | Sí | 8B GLB (sin CRC) |
| Setting Value | `fcbeb4147bc5` | Read/Write | Sí | 8B GLB (sin CRC) |
| Extended Read | `fcff000000ff` | Read | — | Tramas adicionales |
| Pump Key (A) | `fcff0000000a` | Read | No | 64B (32B challenge + 32B pubkey) |
| Pump Key Write (B) | `fcff0000000b` | Write | No | Respuesta cifrada del servidor |

### Historial

| Tipo | Count UUID | Index UUID | Value UUID |
|------|-----------|-----------|-----------|
| Events | `fcbecb3b7bc5` | `fcbecc3b7bc5` | `fcbecd3b7bc5` |
| Alerts | `fcbec83b7bc5` | `fcbec93b7bc5` | `fcbeca3b7bc5` |
| System | `86a5a431-...` | `381ddce9-...` | `ae3022af-...` |

### Device Info (estándar BLE)

| Función | UUID |
|---------|------|
| Serial | `00002a25-...` |
| Firmware | `00002a26-...` |
| Manufacturer | `00002a29-...` |
| Model | `00002a24-...` |

---

## 3. Autenticación

La bomba usa autenticación basada en la **dirección MAC** del dispositivo BLE, no contraseña del usuario.

### Algoritmo

```
password = MD5(mac_bytes + salt)
```

Donde:
- `mac_bytes`: 6 bytes de la dirección MAC del dispositivo BLE (ej: `EC:2A:F0:02:AF:6F` → `EC 2A F0 02 AF 6F`)
- `salt`: Constante de 10 bytes: `4F C2 45 4D 9B 81 59 A4 93 BB`

### Implementación (Kotlin)

```kotlin
private val AUTH_SALT = byteArrayOf(
    0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(),
    0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
)

private fun computeAuthPassword(macAddress: String): ByteArray {
    val macBytes = macAddress.replace(":", "")
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
    return MessageDigest.getInstance("MD5").digest(macBytes + AUTH_SALT)
}
```

### Procedimiento

1. Conectar por BLE al dispositivo (nombre: `YpsoPump_XXXXXXXX`)
2. Descubrir servicios GATT
3. Calcular `MD5(mac + salt)` → 16 bytes
4. Escribir en `CHAR_AUTH_PASSWORD` con `WRITE_TYPE_DEFAULT`

> **Importante**: La autenticación NO requiere ningún PIN o passkey del usuario.
> Solo la dirección MAC es necesaria, y se obtiene automáticamente de la conexión BLE.

---

## 4. Cifrado (XChaCha20-Poly1305)

Después de la autenticación, los comandos sensibles requieren cifrado end-to-end.

### Algoritmo

- **Cifrado**: XChaCha20-Poly1305 IETF (NO es NaCl SecretBox/XSalsa20)
- **Nonce**: 24 bytes aleatorios
- **AAD**: vacío (empty bytes)
- **Tag**: 16 bytes Poly1305 (incluido en el ciphertext)

### Formato en el cable (wire format)

```
┌─────────────────────────────────────┬──────────────┐
│  ciphertext + tag (16 bytes)        │  nonce (24B) │
└─────────────────────────────────────┴──────────────┘
                                        ↑ nonce al FINAL
```

### Derivación de clave compartida

```
shared_secret = X25519(app_private_key, pump_public_key)
shared_key    = HChaCha20(shared_secret, nonce=0x00*16)
```

### Contadores en el plaintext

Antes de cifrar, se añaden 12 bytes de contadores al final del payload:

```
┌──────────────────┬────────────────────┬────────────────────┐
│ payload (N bytes)│ reboot_counter (4B)│ write_counter (8B) │
└──────────────────┴────────────────────┴────────────────────┘
                     LE uint32            LE uint64
```

### Implementación XChaCha20 (Android JCA)

XChaCha20-Poly1305 no está disponible directamente en Android, pero se implementa
combinando:
1. **HChaCha20** (implementación pura Kotlin) para derivar subclave de 32B
2. **ChaCha20-Poly1305** (disponible en Android API 28+) con la subclave derivada

```kotlin
fun xchacha20Poly1305Encrypt(
    plaintext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray
): ByteArray {
    require(nonce.size == 24)
    require(key.size == 32)

    // HChaCha20: derivar subclave de los primeros 16 bytes del nonce
    val subkey = hchacha20(key, nonce.copyOfRange(0, 16))

    // Subnonce: 4 bytes cero + últimos 8 bytes del nonce original
    val subnonce = ByteArray(12)
    System.arraycopy(nonce, 16, subnonce, 4, 8)

    // ChaCha20-Poly1305 estándar con subclave y subnonce
    val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"),
                IvParameterSpec(subnonce))
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}
```

### HChaCha20 (pura Kotlin)

```kotlin
fun hchacha20(key: ByteArray, nonce: ByteArray): ByteArray {
    require(key.size == 32 && nonce.size == 16)
    val constant = "expand 32-byte k".toByteArray(Charsets.US_ASCII)

    val buf = ByteBuffer.wrap(constant + key + nonce).order(ByteOrder.LITTLE_ENDIAN)
    val state = IntArray(16) { buf.int }

    // 20 rounds (10 double-rounds)
    repeat(10) {
        // Column rounds
        quarterRound(state, 0, 4, 8, 12)
        quarterRound(state, 1, 5, 9, 13)
        quarterRound(state, 2, 6, 10, 14)
        quarterRound(state, 3, 7, 11, 15)
        // Diagonal rounds
        quarterRound(state, 0, 5, 10, 15)
        quarterRound(state, 1, 6, 11, 12)
        quarterRound(state, 2, 7, 8, 13)
        quarterRound(state, 3, 4, 9, 14)
    }

    // Tomar posiciones 0-3 y 12-15 (las posiciones de constant y nonce)
    val output = intArrayOf(state[0],state[1],state[2],state[3],
                            state[12],state[13],state[14],state[15])
    val result = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
    output.forEach { result.putInt(it) }
    return result.array()
}
```

### Clase PumpCryptor

Gestiona el estado de cifrado (clave compartida, contadores):

```kotlin
class PumpCryptor(val sharedKey: ByteArray, private val prefs: SharedPreferences) {
    var readCounter: Long     // Contador de lecturas (del pump)
    var writeCounter: Long    // Contador de escrituras (nuestro)
    var rebootCounter: Int    // Contador de reinicios (del pump)

    fun encrypt(payload: ByteArray): ByteArray {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        // Añadir contadores al plaintext
        val buffer = ByteBuffer.allocate(payload.size + 12).order(LE)
        buffer.put(payload)
        buffer.putInt(rebootCounter)
        writeCounter++
        buffer.putLong(writeCounter)
        // Cifrar
        val ciphertext = xchacha20Poly1305Encrypt(buffer.array(), byteArrayOf(), nonce, sharedKey)
        return ciphertext + nonce  // nonce al final
    }

    fun decrypt(data: ByteArray): ByteArray {
        val nonce = data.copyOfRange(data.size - 24, data.size)
        val ciphertext = data.copyOfRange(0, data.size - 24)
        val plaintext = xchacha20Poly1305Decrypt(ciphertext, byteArrayOf(), nonce, sharedKey)
        // Extraer contadores de los últimos 12 bytes
        val counters = ByteBuffer.wrap(plaintext, plaintext.size - 12, 12).order(LE)
        val peerRebootCounter = counters.int
        val numericCounter = counters.long
        // Sincronizar contadores
        if (peerRebootCounter != rebootCounter) {
            rebootCounter = peerRebootCounter
            writeCounter = 0
        }
        readCounter = numericCounter
        return plaintext.copyOfRange(0, plaintext.size - 12)  // Sin contadores
    }
}
```

### Persistencia de clave

La clave se almacena en `SharedPreferences("ypso_crypto")`:

| Key | Tipo | Descripción |
|-----|------|-------------|
| `shared_key` | String (hex) | Clave compartida de 32 bytes |
| `shared_key_expires_at` | Long | Timestamp de expiración |
| `read_counter` | Long | Último contador de lectura |
| `write_counter` | Long | Último contador de escritura |
| `reboot_counter` | Int | Último contador de reinicios |

---

## 5. Intercambio de Claves

El intercambio de claves es necesario para establecer la clave compartida de cifrado.
La clave se obtiene a través de un servidor Proregia (relay) y es válida por aproximadamente
**1 hora** (observado empíricamente: ~82 minutos). Cada intercambio genera una clave diferente.

### Flujo completo

```
┌─────────┐       ┌──────────────┐       ┌──────────┐
│   App   │       │ Relay Server │       │ YpsoPump │
└────┬────┘       └──────┬───────┘       └────┬─────┘
     │                   │                    │
     │ 1. Read CHAR_CMD_READ_A ──────────────►│
     │◄──── 64B: challenge(32B) + pubkey(32B) │
     │                   │                    │
     │ 2. POST /nonce ──►│                    │
     │◄── server_nonce   │                    │
     │                   │                    │
     │ 3. POST /exchange►│                    │
     │   (challenge,     │                    │
     │    pump_pubkey,   │                    │
     │    app_pubkey,    │                    │
     │   integrity_token)│                    │
     │◄── encrypted_resp │                    │
     │                   │                    │
     │ 4. Write CHAR_CMD_WRITE ──────────────►│
     │  (encrypted_resp) │                    │
     │                   │                    │
     │ 5. Derivar clave local:                │
     │    shared = X25519(app_priv, pump_pub) │
     │    key = HChaCha20(shared, 0x00*16)    │
     └────────────────────────────────────────┘
```

### Clave pública de la bomba

La bomba tiene una clave pública X25519 **estable** que no cambia entre reinicios:

```
6cfcc0a5a19221c355ade8b7e6bee361121fe2fe6e5506c063bfce8866bfed46
```

### Par de claves de la app

El par de claves X25519 de la app se genera una vez y se reutiliza en intercambios
posteriores:

```kotlin
fun getOrCreateKeyPair(): X25519KeyPair {
    // Buscar en SharedPreferences("ypso_key_exchange")
    val privHex = prefs.getString("x25519_priv_pkcs8", null)
    val pubHex = prefs.getString("x25519_pub_raw", null)
    if (privHex != null && pubHex != null) {
        return cargarDesdePrefs(privHex, pubHex)
    }
    // Generar nuevo par
    val keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    guardarEnPrefs(keyPair)
    return keyPair
}
```

### Derivación de clave compartida

```kotlin
fun deriveSharedKey(myPrivateKey: PrivateKey, pumpPublicKeyRaw: ByteArray): ByteArray {
    val peerPubKey = rawBytesToPublicKey(pumpPublicKeyRaw)
    val secret = x25519SharedSecret(myPrivateKey, peerPubKey)  // ECDH
    return hchacha20(secret, ByteArray(16))  // HChaCha20 con nonce=0
}
```

### MAC ↔ Serial

La dirección MAC se puede calcular a partir del número de serie:

```kotlin
fun serialToMac(serial: String): String {
    val num = serial.toLong().let { if (it > 10000000) it - 10000000 else it }
    val hex = "%06X".format(num)
    return "EC:2A:F0:${hex[0..1]}:${hex[2..3]}:${hex[4..5]}"
}
// Ejemplo: "10175983" → "EC:2A:F0:02:AF:6F"
```

### Relay Server

El servidor relay actúa como intermediario entre la app y el servidor Proregia de Ypsomed.
Es necesario porque la app de terceros no puede obtener directamente un token de
Play Integrity válido para el package de mylife.

```
┌──────────┐       ┌──────────────┐       ┌────────────┐
│   App    │       │ Relay Server │       │  Proregia  │
│ (nuestra)│       │ (nuestro)    │       │ (Ypsomed)  │
└────┬─────┘       └──────┬───────┘       └─────┬──────┘
     │                    │                     │
     │ 1. POST /nonce     │                     │
     │   {bt_address}     │                     │
     │   ────────────────►│                     │
     │                    │ 2. getNonce(serial) │
     │                    │ ───────────────────►│
     │                    │◄──── server_nonce   │
     │◄── server_nonce    │                     │
     │                    │                     │
     │ 3. POST /exchange  │                     │
     │   {challenge,      │                     │
     │    pump_pubkey,    │                     │
     │    app_pubkey,     │                     │
     │    integrity_token}│                     │
     │   ────────────────►│                     │
     │                    │ 4. encryptKey(      │
     │                    │   challenge,        │
     │                    │   pump_pubkey,      │
     │                    │   app_pubkey,       │
     │                    │   integrity_token)  │
     │                    │ ───────────────────►│
     │                    │◄── encrypted_resp   │
     │◄── encrypted_resp  │                     │
     └────────────────────┴─────────────────────┘
```

```kotlin
private fun performKeyExchangeViaRelay(relayUrl: String) {
    // 1. Re-autenticar (la sesión puede haber expirado)
    pumpManager?.authenticate { authOk ->
        // 2. Leer clave pública de la bomba con reintentos
        readPumpKeyWithRetry(maxRetries = 5, delay = 1000ms) { pumpKeyData ->
            val challenge = pumpKeyData.copyOfRange(0, 32)
            val pumpPubKey = pumpKeyData.copyOfRange(32, 64)

            // 3. Llamar al relay
            val response = callRelayServer(relayUrl, challenge, pumpPubKey, appPubKey)

            // 4. Escribir respuesta a la bomba
            pumpManager?.writeMultiFrame(CHAR_CMD_WRITE, response) { ok ->
                // 5. Derivar clave compartida
                val sharedKey = deriveSharedKey(appPrivKey, pumpPubKey)
                val cryptor = PumpCryptor.create(context, sharedKey)
                pumpManager?.pumpCryptor = cryptor
            }
        }
    }
}
```

### Token de Play Integrity

El intercambio de claves requiere un **token de Play Integrity** de Google para
autenticar la petición ante el servidor Proregia de Ypsomed.

#### Propiedades del token

| Propiedad | Valor |
|-----------|-------|
| Formato | Blob opaco (NO es JWT) |
| Uso | **Un solo uso** (anti-replay de Google) |
| TTL | ~1 hora (Firebase App Check) |
| Verificación | Server-side por Google Play Services |
| Vinculación | Al `server_nonce` específico de la petición |

#### El token NO es JWT

A diferencia de JWT, el token de Play Integrity es un blob opaco generado por
Google Play Services en el dispositivo. No se puede decodificar ni inspeccionar
desde la app. Solo el servidor de Google puede verificarlo y extraer el veredicto
(integridad del dispositivo, identidad del paquete, licencia).

#### Un solo uso (anti-replay)

Google implementa protección anti-replay: cada token solo puede verificarse una
vez en los servidores de Google. Si se intenta reutilizar, la verificación falla.
Esto significa que **cada intercambio de claves requiere un token nuevo**.

#### Vinculación por bomba (nonce chain)

Un token de Play Integrity **no se puede reutilizar entre diferentes bombas** por
tres razones:

1. **El `server_nonce` es por bomba**: `getNonce(bt_address)` genera un nonce
   específico para la dirección Bluetooth de la bomba.
2. **El nonce está embebido en el token**: El `server_nonce` se incluye como
   `requestHash` al solicitar el token de Play Integrity. Google lo vincula al
   veredicto.
3. **Anti-replay**: Incluso si el nonce fuera el mismo, Google rechaza la
   reutilización del token.

```
bt_address → getNonce(serial) → server_nonce
                                     ↓
                         Play Integrity request(
                           requestHash = SHA256(server_nonce)
                         )
                                     ↓
                               token (opaco)
                                     ↓
                         encryptKey(token, challenge, ...)
```

#### Flujo de obtención del token

En nuestro caso, el token se obtiene en el **relay server** (que ejecuta en un
teléfono rooteado con la app mylife y Frida):

1. **Frida intercepta** la llamada a Play Integrity dentro de la app mylife
2. **Inyecta el `server_nonce`** como `requestHash`
3. **Captura el token** generado por Google Play Services
4. **Lo envía a Proregia** como `message_attestation_object` en la petición gRPC

> **Nota**: El relay server es necesario porque solo la app oficial mylife
> (con su package name y signing key) puede obtener un token de Play Integrity
> que Proregia acepte. Una app de terceros no puede obtener este token directamente.

### Validez de la clave compartida

La clave compartida derivada del intercambio tiene una **validez limitada de
aproximadamente 1 hora** (observado empíricamente: ~82 minutos).

| Aspecto | Detalle |
|---------|---------|
| Duración real | ~1 hora (observado: 82 min) |
| Quién la invalida | La bomba (server-side) |
| Indicador de expiración | `getSystemStatus()` falla al descifrar |
| Código Python (Uneo7) | Asume 28 días — valor arbitrario del cache local |
| Cada intercambio | Genera una clave **diferente** |

#### Detección de expiración

La bomba no comunica cuándo expira la clave. La única forma de detectarlo es
intentar descifrar una respuesta cifrada y verificar si falla:

```kotlin
// Dentro de withPumpConnection():
val status = suspendGetSystemStatus()
if (status == null && bleManager.lastDecryptFailed) {
    // Clave muerta → renovar inmediatamente, sin reintentos
    throw EncryptionKeyExpiredException()
}
```

#### Renovación automática

El SDK renueva la clave automáticamente cuando detecta expiración:

```
1. Comando (bolus/TBR/etc) → withPumpConnection()
2. getSystemStatus() → decrypt falla
3. EncryptionKeyExpiredException → withPumpConnectionAndKeyRetry() la captura
4. suspendAutoKeyExchange() → nuevo intercambio con relay server
5. Reintenta el comando original con la nueva clave
```

> **Importante**: Al detectar fallo de descifrado, se renueva inmediatamente
> sin reintentar con la clave muerta. Reintentar con una clave expirada solo
> desperdicia tiempo (~1.5s por intento).

---

## 6. Protocolo de Tramas (Framing)

El protocolo ProBluetooth fragmenta datos mayores de 19 bytes en múltiples tramas BLE.

### Formato de trama

```
┌────────┬──────────────────────────┐
│ Header │   Datos (máx 19 bytes)   │
│  (1B)  │                          │
└────────┴──────────────────────────┘
```

**Header**: `((frame_idx + 1) << 4 & 0xF0) | (total_frames & 0x0F)`

- Nibble alto: número de trama actual (1-indexed)
- Nibble bajo: total de tramas

### Ejemplos

| Caso | Header | Significado |
|------|--------|-------------|
| Trama única | `0x11` | Trama 1 de 1 |
| Primera de 3 | `0x13` | Trama 1 de 3 |
| Segunda de 3 | `0x23` | Trama 2 de 3 |
| Tercera de 3 | `0x33` | Trama 3 de 3 |

### Implementación

```kotlin
object YpsoFraming {
    private const val MAX_PAYLOAD_PER_FRAME = 19

    fun chunkPayload(data: ByteArray): List<ByteArray> {
        val totalFrames = maxOf(1, (data.size + MAX_PAYLOAD_PER_FRAME - 1) / MAX_PAYLOAD_PER_FRAME)
        val frames = mutableListOf<ByteArray>()

        for (idx in 0 until totalFrames) {
            val start = idx * MAX_PAYLOAD_PER_FRAME
            val end = minOf(start + MAX_PAYLOAD_PER_FRAME, data.size)
            val chunk = data.copyOfRange(start, end)
            val header = (((idx + 1) shl 4) and 0xF0) or (totalFrames and 0x0F)
            frames.add(byteArrayOf(header.toByte()) + chunk)
        }
        return frames
    }

    fun parseMultiFrameRead(frames: List<ByteArray>): ByteArray {
        return frames.flatMap { it.drop(1) }.toByteArray()
    }

    fun getTotalFrames(firstByte: Byte): Int {
        return (firstByte.toInt() and 0x0F).let { if (it == 0) 1 else it }
    }
}
```

### Lectura multi-trama

Para leer datos, se lee la primera trama de la característica principal.
Si `total_frames > 1`, se leen las tramas adicionales de `CHAR_EXTENDED_READ`:

```kotlin
fun readExtended(firstUuid: UUID, callback: (ByteArray?) -> Unit) {
    readCharacteristic(firstChar).with { _, data ->
        val firstFrame = data.value
        val totalFrames = YpsoFraming.getTotalFrames(firstFrame[0])

        if (totalFrames <= 1) {
            callback(firstFrame.copyOfRange(1, firstFrame.size))
        } else {
            // Leer tramas restantes de CHAR_EXTENDED_READ
            readRemainingFrames(extChar, totalFrames - 1, frames) { allFrames ->
                callback(YpsoFraming.parseMultiFrameRead(allFrames))
            }
        }
    }
}
```

### Escritura multi-trama

Las tramas se escriben secuencialmente. Cada escritura BLE debe completarse
antes de enviar la siguiente:

```kotlin
private fun writeFramesSequentially(
    char: BluetoothGattCharacteristic,
    frames: List<ByteArray>,
    index: Int,
    callback: (Boolean) -> Unit
) {
    if (index >= frames.size) { callback(true); return }
    writeCharacteristic(char, frames[index], WRITE_TYPE_DEFAULT)
        .with { _, _ -> writeFramesSequentially(char, frames, index + 1, callback) }
        .fail { _, status -> callback(false) }
        .enqueue()
}
```

---

## 7. CRC16

La bomba usa un CRC personalizado basado en el polinomio CRC-32 `0x04C11DB7`
con bitstuffing, devolviendo solo los 16 bits inferiores en formato Little Endian.

### Bitstuffing

Antes de calcular el CRC, los datos se reorganizan en bloques de 4 bytes
con el orden de bytes invertido:

```
Input:  [A, B, C, D, E, F, G, H]
Output: [D, C, B, A, H, G, F, E]
```

### Implementación

```kotlin
object YpsoCrc {
    private const val CRC_POLY = 0x04C11DB7L

    private val CRC_TABLE = LongArray(256).also { table ->
        for (idx in 0 until 256) {
            var v = idx.toLong() shl 24
            for (bit in 0 until 8) {
                v = if (v and 0x80000000L != 0L) {
                    ((v shl 1) and 0xFFFFFFFFL) xor CRC_POLY
                } else {
                    (v shl 1) and 0xFFFFFFFFL
                }
            }
            table[idx] = v
        }
    }

    private fun bitstuff(data: ByteArray): ByteArray {
        val blockCount = (data.size + 3) / 4
        val stuffed = ByteArray(blockCount * 4)
        for (block in 0 until blockCount) {
            val base = block * 4
            for (idx in 0 until 4) {
                stuffed[base + 3 - idx] = if (base + idx < data.size) data[base + idx] else 0
            }
        }
        return stuffed
    }

    fun crc16(payload: ByteArray): ByteArray {
        var crc = 0xFFFFFFFFL
        for (byte in bitstuff(payload)) {
            val tableIdx = ((crc shr 24) xor (byte.toLong() and 0xFF)) and 0xFF
            crc = ((crc shl 8) and 0xFFFFFFFFL) xor CRC_TABLE[tableIdx.toInt()]
        }
        val result = (crc and 0xFFFFL).toInt()
        return byteArrayOf((result and 0xFF).toByte(), ((result shr 8) and 0xFF).toByte())
    }

    fun appendCrc(payload: ByteArray): ByteArray = payload + crc16(payload)

    fun isValid(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val data = payload.copyOfRange(0, payload.size - 2)
        val crcBytes = payload.copyOfRange(payload.size - 2, payload.size)
        return crc16(data).contentEquals(crcBytes)
    }
}
```

### Cuándo se usa CRC

| Comando | Usa CRC |
|---------|---------|
| Bolus | Sí |
| System Status | Sí (en respuesta) |
| Bolus Status | Sí (en respuesta) |
| Date/Time | Sí |
| TBR | **No** (usa GLB) |
| Settings | **No** (usa GLB) |
| History Count | **No** (usa GLB) |
| History Index | **No** (usa GLB) |
| History Value | Sí (en respuesta) |

---

## 8. Variables Seguras GLB

GLB es un formato de auto-validación: un valor de 32 bits seguido de su complemento
bit a bit. Total: 8 bytes.

```
┌──────────────────┬──────────────────┐
│  value (u32 LE)  │  ~value (u32 LE) │
└──────────────────┴──────────────────┘
```

### Ejemplo

Para el valor `25`:
```
25 en hex: 0x19000000 (LE)
~25:       0xE6FFFFFF (LE)
GLB(25):   19 00 00 00 E6 FF FF FF
```

### Implementación

```kotlin
object YpsoGlb {
    fun encode(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        buf.putInt(value.inv())
        return buf.array()
    }

    fun decode(data: ByteArray): Int {
        require(data.size >= 8)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val value = buf.int
        val check = buf.int
        require(value == check.inv()) { "GLB integrity check failed" }
        return value
    }
}
```

### Usos de GLB

- **Settings**: Tanto el índice como el valor se codifican en GLB
- **TBR**: Dos valores GLB consecutivos (porcentaje + duración)
- **Historial**: Count e Index se codifican en GLB

---

## 9. Sincronización de Contadores

El cifrado usa contadores (`reboot_counter` + `write_counter`) que deben estar
sincronizados entre la app y la bomba. Si los contadores no coinciden, la bomba
rechaza los comandos cifrados.

### Problema

Cuando se establece una nueva clave, la app no conoce el `reboot_counter` actual
de la bomba. Si envía un write con `reboot_counter = 0` y la bomba espera otro valor,
el comando será rechazado.

### Solución: Auto-sync

Antes de cada escritura cifrada, se verifica que los contadores estén sincronizados.
Si no lo están, se realiza una lectura del estado del sistema (que es una operación
de lectura, no escritura) para aprender los contadores:

```kotlin
private var _countersSynced = true

var pumpCryptor: PumpCryptor? = null
    set(value) {
        field = value
        _countersSynced = (value == null)  // nueva clave = necesita sync
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
```

### Flujo

```
1. Se asigna nueva clave → _countersSynced = false
2. App intenta enviar bolus/TBR/etc
3. ensureCountersSynced() detecta que no está sincronizado
4. Lee System Status → decrypt aprende reboot_counter del pump
5. _countersSynced = true
6. Procede con la escritura cifrada (con reboot_counter correcto)
```

---

## 10. Comandos de la Bomba

### 10.1 Estado del Sistema

Lee el estado actual de la bomba: modo de entrega, insulina restante, batería.

**Característica**: `CHAR_SYSTEM_STATUS`
**Pipeline**: Read → Decrypt → CRC check → Parse

**Formato del payload** (6 bytes sin CRC):

| Offset | Tamaño | Descripción |
|--------|--------|-------------|
| 0 | 1 | Delivery mode |
| 1 | 4 | Insulina restante (u32 LE, centésimas de unidad) |
| 5 | 1 | Batería (porcentaje, 0-100) |

**Modos de entrega**:

| Valor | Significado |
|-------|-------------|
| 0 | Stopped |
| 1 | Basal |
| 2 | TBR Active |
| 3 | Fast Bolus |
| 4 | Extended Bolus |
| 5 | Bolus + Basal |
| 6 | Priming |
| 7 | Paused |

```kotlin
fun getSystemStatus(callback: (SystemStatusData?) -> Unit) {
    readExtended(CHAR_SYSTEM_STATUS) { rawData ->
        val data = decryptIfNeeded(rawData)
        if (YpsoCrc.isValid(data)) {
            val payload = data.copyOfRange(0, data.size - 2)
            val mode = payload[0].toInt() and 0xFF
            val insulin = ByteBuffer.wrap(payload, 1, 4).order(LE).int / 100f
            val battery = payload[5].toInt() and 0xFF
            callback(SystemStatusData(mode, DeliveryMode.name(mode), insulin, battery))
        }
    }
}
```

### 10.2 Bolus

**Característica**: `CHAR_BOLUS_START_STOP`
**Pipeline**: Payload(13B) → CRC → Encrypt → Frame → Write

**Formato del payload** (13 bytes):

| Offset | Tamaño | Descripción |
|--------|--------|-------------|
| 0 | 4 | Total unidades × 100 (u32 LE) |
| 4 | 4 | Duración en minutos (u32 LE, 0 = bolo rápido) |
| 8 | 4 | Unidades inmediatas × 100 (u32 LE, para combo) |
| 12 | 1 | Tipo: 1 = rápido, 2 = extendido |

```kotlin
fun startBolus(totalUnits: Float, durationMinutes: Int = 0,
               immediateUnits: Float = 0f, callback: (Boolean) -> Unit) {
    val totalScaled = (totalUnits * 100).toInt().coerceIn(1, 2500)
    val immediateScaled = (immediateUnits * 100).toInt()
    val bolusType: Byte = if (durationMinutes == 0) 1 else 2

    val payload = ByteBuffer.allocate(13).order(LE)
        .putInt(totalScaled)
        .putInt(durationMinutes)
        .putInt(immediateScaled)
        .put(bolusType)
        .array()

    sendBolusCommand(payload, callback)  // +CRC → encrypt → frame → write
}
```

**Cancelar bolo**: 13 bytes de ceros con el último byte = tipo de bolo:

```kotlin
fun cancelBolus(kind: String = "fast", callback: (Boolean) -> Unit) {
    val bolusType = if (kind == "fast") 1 else 2
    val payload = ByteArray(13).also { it[12] = bolusType.toByte() }
    sendBolusCommand(payload, callback)
}
```

**Estado del bolo**: Lectura de `CHAR_BOLUS_STATUS` (cifrado, con CRC).

**Notificaciones de bolo**: `CHAR_BOLUS_NOTIFICATION` (13B, NO cifrado):

| Offset | Tamaño | Descripción |
|--------|--------|-------------|
| 0 | 1 | Estado bolo rápido (0=idle, 1=delivering, 3=cancelled, 4=completed) |
| 1 | 4 | Secuencia bolo rápido |
| 5 | 1 | Estado bolo extendido |
| 6 | 4 | Secuencia bolo extendido |

### 10.3 TBR (Tasa Basal Temporal)

**Característica**: `CHAR_TBR_START_STOP`
**Pipeline**: GLB(16B) → Encrypt → Frame → Write (SIN CRC)

**Formato**: Dos variables GLB consecutivas, sin CRC:

```
┌──────────────────────────────┬──────────────────────────────┐
│  GLB(porcentaje) — 8 bytes   │  GLB(duración_min) — 8 bytes │
└──────────────────────────────┴──────────────────────────────┘
```

> **MUY IMPORTANTE**: El porcentaje es el valor RAW (25 para 25%), NO centi-porcentaje.
> Usar centi-porcentaje (2500) causa error 130 (0x82 — valor rechazado por la bomba).
> Usar formatos de payload distintos a 16B causa error 138 (0x8A — formato no reconocido).

```kotlin
fun startTbr(percent: Int, durationMinutes: Int, callback: (Boolean) -> Unit) {
    val payload = ByteBuffer.allocate(16).order(LE)
        .putInt(percent)                 // raw: 25 para 25%
        .putInt(percent.inv())           // complemento GLB
        .putInt(durationMinutes)         // minutos: 30
        .putInt(durationMinutes.inv())   // complemento GLB
        .array()

    ensureCountersSynced {
        val encrypted = encryptIfNeeded(payload)
        val frames = YpsoFraming.chunkPayload(encrypted)
        writeFramesSequentially(charTbrStartStop, frames, 0, callback)
    }
}
```

**Cancelar TBR**: Enviar 100% con 0 minutos:

```kotlin
fun cancelTbr(callback: (Boolean) -> Unit) {
    val payload = ByteBuffer.allocate(16).order(LE)
        .putInt(100).putInt(100.inv())    // 100% = tasa normal
        .putInt(0).putInt(0.inv())        // 0 minutos
        .array()
    // ... encrypt → frame → write
}
```

**Parámetros válidos**:
- Porcentaje: 0-200 (0 = suspender, 100 = normal, 200 = doble)
- Duración: 15-1440 minutos (múltiplos de 15)

### 10.4 Perfiles Basales

La bomba tiene dos programas de perfil basal: **A** y **B**, cada uno con 24 tasas
horarias.

**Características**: `CHAR_SETTING_ID` + `CHAR_SETTING_VALUE`
**Pipeline**: GLB → Encrypt → Frame → Write/Read (SIN CRC)

**Índices de settings**:

| Programa | Tasa 00:00 | Tasa 23:00 | Valor del programa |
|----------|-----------|-----------|-------------------|
| A | 14 | 37 | 3 |
| B | 38 | 61 | 10 |

**Programa activo**: Setting index = 1

**Leer una tasa horaria**:

```
1. Escribir GLB(index) → CHAR_SETTING_ID    [encrypt → frame]
2. Leer CHAR_SETTING_VALUE                   [decrypt → GLB decode]
3. Valor = centésimas de U/h (dividir entre 100)
```

**Escribir una tasa horaria**:

```
1. Escribir GLB(index) → CHAR_SETTING_ID    [encrypt → frame]
2. Escribir GLB(valor) → CHAR_SETTING_VALUE  [encrypt → frame]
   donde valor = rate_U_h × 100 (entero)
```

**Leer perfil completo (24 tasas)**:

```kotlin
fun readBasalProfile(program: Char, callback: (List<Float>?) -> Unit) {
    val startIndex = when (program) {
        'A' -> SettingsIndex.PROGRAM_A_START  // 14
        'B' -> SettingsIndex.PROGRAM_B_START  // 38
        else -> return
    }
    // Leer secuencialmente los índices startIndex..startIndex+23
    readBasalRateSequential(startIndex, startIndex + 23, rates, callback)
}
```

**Escribir perfil completo**:

```kotlin
fun writeBasalProfile(program: Char, rates: List<Float>, callback: (Boolean) -> Unit) {
    val startIndex = when (program) {
        'A' -> SettingsIndex.PROGRAM_A_START
        'B' -> SettingsIndex.PROGRAM_B_START
        else -> return
    }
    // Escribir secuencialmente 24 settings
    rates.forEachIndexed { hour, rate ->
        writeSetting(startIndex + hour, (rate * 100).toInt()) { success ->
            // Continuar con la siguiente o reportar error
        }
    }
}
```

**Activar un programa**:

```kotlin
fun activateProfile(program: Char) {
    val value = when (program) {
        'A' -> SettingsIndex.PROGRAM_A_VALUE  // 3
        'B' -> SettingsIndex.PROGRAM_B_VALUE  // 10
        else -> return
    }
    writeSetting(SettingsIndex.ACTIVE_PROGRAM, value) { success -> ... }
}
```

**Leer programa activo**:

```kotlin
fun readActiveProgram(callback: (Char?) -> Unit) {
    readSetting(SettingsIndex.ACTIVE_PROGRAM) { value ->
        when (value) {
            SettingsIndex.PROGRAM_A_VALUE -> callback('A')
            SettingsIndex.PROGRAM_B_VALUE -> callback('B')
            else -> callback(null)
        }
    }
}
```

### 10.5 Sincronización de Hora

**Características**: `CHAR_SYSTEM_DATE` + `CHAR_SYSTEM_TIME`
**Pipeline**: Payload → CRC → Encrypt → Frame → Write

**Fecha** (4 bytes):

| Offset | Tamaño | Descripción |
|--------|--------|-------------|
| 0 | 2 | Año (u16 LE) |
| 2 | 1 | Mes (1-12) |
| 3 | 1 | Día (1-31) |

**Hora** (3 bytes):

| Offset | Tamaño | Descripción |
|--------|--------|-------------|
| 0 | 1 | Hora (0-23) |
| 1 | 1 | Minuto (0-59) |
| 2 | 1 | Segundo (0-59) |

```kotlin
fun syncTime(callback: (Boolean) -> Unit) {
    val now = Calendar.getInstance()

    val datePayload = ByteBuffer.allocate(4).order(LE)
        .putShort(now.get(YEAR).toShort())
        .put((now.get(MONTH) + 1).toByte())
        .put(now.get(DAY_OF_MONTH).toByte())
        .array()

    val timePayload = byteArrayOf(
        now.get(HOUR_OF_DAY).toByte(),
        now.get(MINUTE).toByte(),
        now.get(SECOND).toByte()
    )

    ensureCountersSynced {
        // Escribir fecha con CRC + cifrado
        val dateWithCrc = encryptIfNeeded(YpsoCrc.appendCrc(datePayload))
        writeFrames(dateChar, dateWithCrc) { dateOk ->
            // Escribir hora con CRC + cifrado
            val timeWithCrc = encryptIfNeeded(YpsoCrc.appendCrc(timePayload))
            writeFrames(timeChar, timeWithCrc) { timeOk ->
                callback(timeOk)
            }
        }
    }
}
```

### 10.6 Historial

La bomba almacena tres tipos de historial: eventos, alertas y sistema.

**Flujo de lectura**:

```
1. Leer COUNT        → GLB → decrypt → obtener total de entradas
2. Escribir INDEX    → GLB → encrypt → seleccionar entrada N
3. Leer VALUE        → decrypt → CRC check → parse HistoryEntry
4. Repetir 2-3 para cada entrada
```

**Formato de entrada de historial** (17 bytes):

| Offset | Tamaño | Descripción |
|--------|--------|-------------|
| 0 | 4 | Timestamp Unix (u32 LE) |
| 4 | 1 | Tipo de evento |
| 5 | 2 | Valor 1 (u16 LE) |
| 7 | 2 | Valor 2 (u16 LE) |
| 9 | 2 | Valor 3 (u16 LE) |
| 11 | 4 | Secuencia (u32 LE) |
| 15 | 2 | Índice (u16 LE) |

**Tipos de eventos principales**:

| ID | Evento | Valores |
|----|--------|---------|
| 1 | Bolo rápido iniciado | v1=centésimas_U |
| 2 | Bolo rápido completado | v1=centésimas_U |
| 3 | Bolo rápido cancelado | v1=entregadas, v2=solicitadas |
| 6 | Tasa basal cambiada | v1=nueva_tasa |
| 7 | Programa basal cambiado | v1=programa |
| 8 | TBR iniciado | v1=porcentaje, v2=duración |
| 9 | TBR completado | |
| 10 | TBR cancelado | |
| 12 | Cartucho cambiado | |
| 13 | Batería cambiada | |
| 14 | Fecha/hora establecida | |
| 100 | Reservorio bajo | |
| 101 | Batería baja | |
| 102 | Oclusión | |

---

## 11. Flujo Completo de Conexión

El flujo automático de conexión (`autoConnect`) sigue estos pasos:

```
┌────────────────────────────────────────────────────┐
│ 1. ESCANEO BLE                                     │
│    - Buscar dispositivo "YpsoPump_XXXXXXXX"        │
│    - Timeout: 15 segundos                          │
├────────────────────────────────────────────────────┤
│ 2. CONEXIÓN                                        │
│    - connect(device)                               │
│    - Descubrir servicios GATT                      │
│    - Mapear características                        │
│    - Habilitar notificaciones (Bolus, Status)      │
├────────────────────────────────────────────────────┤
│ 3. AUTENTICACIÓN                                   │
│    - Calcular MD5(mac + salt)                      │
│    - Escribir en CHAR_AUTH_PASSWORD                │
├────────────────────────────────────────────────────┤
│ 4. CARGAR CLAVE DE CIFRADO                         │
│    - Buscar en SharedPreferences("ypso_crypto")    │
│    - Si existe: asignar PumpCryptor                │
│    - Si no existe: → paso 4b (key exchange)        │
├────────────────────────────────────────────────────┤
│ 4b. INTERCAMBIO DE CLAVES (si necesario)           │
│    - Si hay URL relay guardada: auto key exchange  │
│    - Si no: mostrar UI de key exchange al usuario  │
├────────────────────────────────────────────────────┤
│ 5. VALIDAR CLAVE                                   │
│    - Leer System Status (descifra + CRC check)     │
│    - Si falla: → paso 4b (clave inválida)          │
│    - Si éxito: contadores sincronizados            │
├────────────────────────────────────────────────────┤
│ 6. INICIALIZACIÓN POST-CONEXIÓN                    │
│    - Sincronizar hora                              │
│    - Leer programa activo                          │
│    - Actualizar UI con estado de la bomba          │
│    - Marcar como "Authenticated + Encrypted"       │
└────────────────────────────────────────────────────┘
```

### Código simplificado

```kotlin
fun autoConnect(context: Context) {
    _isAutoConnecting.value = true

    // 1. Escanear
    startScan()  // BLE scan con filtro "YpsoPump_"

    // 2. Al encontrar → conectar
    pumpManager?.connect(device)?.enqueue()

    // 3. Autenticar
    pumpManager?.authenticate { authOk ->
        if (!authOk) { error("Auth failed"); return }

        // 4. Cargar clave
        val cryptor = YpsoKeyExchange(context, pumpManager).loadCryptor()
        if (cryptor != null) {
            pumpManager?.pumpCryptor = cryptor
            // 5. Validar
            pumpManager?.getSystemStatus { status ->
                if (status != null) {
                    // 6. Éxito
                    onKeyValidated(status)
                } else {
                    // Clave inválida → auto-recovery
                    autoRecoverKey()
                }
            }
        } else {
            autoRecoverKey()
        }
    }
}
```

---

## 12. Recuperación Automática de Clave

Cuando la clave de cifrado expira o es invalidada (por ejemplo, al emparejar
con otra app), el sistema intenta recuperarla automáticamente.

### Flujo

```
1. getSystemStatus() falla con BAD_DECRYPT
2. Se invalida pumpCryptor (= null)
3. Se llama autoRecoverKey()
4. Si hay URL relay guardada en SharedPreferences:
   a. Re-autenticar
   b. Leer clave pública de la bomba
   c. Llamar al relay server
   d. Escribir respuesta a la bomba
   e. Derivar nueva clave compartida
   f. Validar con getSystemStatus()
   g. Continuar con flujo normal
5. Si no hay URL relay:
   a. Mostrar card de Key Exchange al usuario
   b. El usuario introduce la URL y pulsa el botón
```

### Persistencia de URL relay

```kotlin
private fun saveRelayUrl(url: String) {
    appContext?.getSharedPreferences("ypso_key_exchange", MODE_PRIVATE)
        ?.edit()?.putString("relay_url", url)?.apply()
}

private fun loadRelayUrl(): String? {
    return appContext?.getSharedPreferences("ypso_key_exchange", MODE_PRIVATE)
        ?.getString("relay_url", null)
}
```

La URL se guarda automáticamente cuando el usuario realiza un key exchange manual
exitoso. En conexiones posteriores, si la clave falla, se usa la URL guardada
para intentar una renovación automática sin intervención del usuario.

---

## 13. Estructura del Proyecto

```
app/src/main/java/com/ypsopump/controller/
├── MainActivity.kt              # Activity principal, UI wiring
├── model/
│   ├── ConnectionState.kt       # Sealed class de estados de conexión
│   └── PumpStatus.kt            # Data class para UI de estado
├── ui/
│   ├── MainViewModel.kt         # ViewModel central, orquesta comandos
│   └── DiscoveredDevice.kt      # Dispositivos BLE encontrados
└── ble/
    ├── YpsoPumpManager.kt       # BLE manager (Nordic BleManager)
    ├── YpsoCrypto.kt            # X25519, HChaCha20, XChaCha20-Poly1305, PumpCryptor
    ├── YpsoKeyExchange.kt       # Intercambio de claves con Proregia/relay
    ├── YpsoFraming.kt           # Protocolo multi-trama ProBluetooth
    ├── YpsoCrc.kt               # CRC16 personalizado
    ├── YpsoCommand.kt           # Constantes, GLB, data classes
    ├── YpsoPumpUuids.kt         # UUIDs de servicios y características
    └── ProregiaClient.kt        # Cliente gRPC para servidor Proregia
```

### Dependencias clave

```groovy
// BLE
implementation "no.nordicsemi.android:ble:2.7.4"

// Crypto (nativo Android API 28+ para ChaCha20-Poly1305)
// X25519 nativo Android API 33+
// No se necesita libsodium

// gRPC (para comunicación con Proregia)
implementation "io.grpc:grpc-okhttp:..."
implementation "io.grpc:grpc-protobuf-lite:..."
implementation "io.grpc:grpc-stub:..."
```

---

## 14. Referencia Rápida de Formatos

### Resumen de pipelines por comando

| Comando | Char | Payload | +CRC | GLB | Pipeline |
|---------|------|---------|------|-----|----------|
| **Bolus Start** | `e18b` | 13B | Sí | No | payload → CRC → encrypt → frame → write |
| **Bolus Cancel** | `e18b` | 13B | Sí | No | payload → CRC → encrypt → frame → write |
| **TBR Start** | `e38b` | 16B | No | Sí (×2) | GLB×2 → encrypt → frame → write |
| **TBR Cancel** | `e38b` | 16B | No | Sí (×2) | GLB×2 → encrypt → frame → write |
| **System Status** | `e48b` | 6B resp | Sí | No | read → decrypt → CRC → parse |
| **Bolus Status** | `e28b` | Variable | Sí | No | read → decrypt → CRC → parse |
| **Setting Read** | `b314`/`b414` | 8B | No | Sí | GLB(idx) → enc → write; read → dec → GLB |
| **Setting Write** | `b314`/`b414` | 8B | No | Sí | GLB(idx) → enc → write; GLB(val) → enc → write |
| **Sync Date** | `dc3b` | 4B | Sí | No | payload → CRC → encrypt → frame → write |
| **Sync Time** | `dd3b` | 3B | Sí | No | payload → CRC → encrypt → frame → write |
| **History Count** | Varios | 8B resp | No | Sí | read → decrypt → GLB decode |
| **History Index** | Varios | 8B | No | Sí | GLB(idx) → encrypt → frame → write |
| **History Value** | Varios | 17B resp | Sí | No | read → decrypt → CRC → parse |
| **Auth** | `b214` | 16B | No | No | MD5(mac+salt) → write (sin cifrado) |

### Errores BLE conocidos

| Código | Hex | Significado |
|--------|-----|-------------|
| 130 | 0x82 | Formato reconocido, valores rechazados |
| 133 | 0x85 | GATT error genérico (conexión perdida) |
| 138 | 0x8A | Formato/longitud no reconocido |
| 139 | 0x8B | Recurso ocupado o timeout de escritura |

### Datos del pump de prueba

| Campo | Valor |
|-------|-------|
| Serial | 10175983 |
| MAC | EC:2A:F0:02:AF:6F |
| Firmware | V05.02.03 |
| Pump PubKey | `6cfcc0a5a19221c355ade8b7e6bee361121fe2fe6e5506c063bfce8866bfed46` |

---

## Notas Adicionales

### Limitaciones conocidas

1. **X25519 requiere API 33+**: La generación de claves X25519 nativa de Android
   solo está disponible desde Android 13 (API 33). Para versiones anteriores,
   se necesitaría Bouncy Castle o libsodium.

2. **ChaCha20-Poly1305 requiere API 28+**: Disponible desde Android 9.

3. **Validez de la clave**: La clave compartida es válida por aproximadamente
   **1 hora** (~82 min observados). La bomba la invalida por tiempo, no por
   emparejamiento con otra app. El SDK detecta la expiración al fallar el
   descifrado y renueva automáticamente si hay URL de relay configurada.

4. **Orden de bytes**: Todo el protocolo usa **Little Endian** excepto los hashes
   (MD5 y los headers BLE estándar que usan Big Endian).

### Código de referencia

- **Python (Uneo7/Ypso)**: `/Users/victor/Developer/CamAPS/Ypso-main/`
  - `pump/crypto.py` — Cifrado y derivación de claves
  - `pump/sdk.py` — Comandos de la bomba
  - `pump/utils.py` — Framing y utilidades
  - `pump/crc.py` — CRC16

- **Scripts de emparejamiento**: `~/Ypso/pairing.py`
