# YpsoPump Integration — Complete Technical Documentation

Documentation of the YpsoPump insulin pump BLE protocol and its implementation
in Android (Kotlin). Based on reverse engineering of the official **mylife YpsoPump** app
and the reference project [Uneo7/Ypso](https://github.com/Uneo7/Ypso) (Python).

---

## Table of Contents

1. [General Architecture](#1-general-architecture)
2. [BLE Services and Characteristics](#2-ble-services-and-characteristics)
3. [Authentication](#3-authentication)
4. [Encryption (XChaCha20-Poly1305)](#4-encryption-xchacha20-poly1305)
5. [Key Exchange](#5-key-exchange)
    - [Play Integrity Token](#play-integrity-token)
    - [Shared Key Validity](#shared-key-validity)
6. [Framing Protocol](#6-framing-protocol)
7. [CRC16](#7-crc16)
8. [GLB Secure Variables](#8-glb-secure-variables)
9. [Counter Synchronization](#9-counter-synchronization)
10. [Pump Commands](#10-pump-commands)
    - [System Status](#101-system-status)
    - [Bolus](#102-bolus)
    - [TBR (Temporary Basal Rate)](#103-tbr-temporary-basal-rate)
    - [Basal Profiles](#104-basal-profiles)
    - [Time Synchronization](#105-time-synchronization)
    - [History](#106-history)
11. [Complete Connection Flow](#11-complete-connection-flow)
12. [Automatic Key Recovery](#12-automatic-key-recovery)
13. [Project Structure](#13-project-structure)
14. [Quick Format Reference](#14-quick-format-reference)

---

## 1. General Architecture

The YpsoPump exposes a proprietary BLE protocol called **ProBluetooth** with two main GATT services:

```
┌──────────────┐        BLE GATT        ┌────────────────────┐
│  Android App │ ◄─────────────────────► │    YpsoPump        │
│              │                         │                    │
│  BleManager  │   Service: Security     │  Authentication    │
│  (Nordic)    │   Service: Write        │  Commands          │
│              │   Service: DeviceInfo   │  Status/History    │
└──────────────┘                         └────────────────────┘
```

**Data sending pipeline (writing to the pump):**

```
Payload → [+CRC16] → [Encrypt] → Fragment into frames → BLE Write
```

**Data receiving pipeline (reading from the pump):**

```
BLE Read → Assemble frames → [Decrypt] → [Verify CRC16] → Payload
```

> Note: Brackets `[]` indicate optional steps depending on the command type.
> Some commands (such as TBR and Settings) use GLB format without CRC.

---

## 2. BLE Services and Characteristics

### Services

| Service | UUID | Description |
|---------|------|-------------|
| Security | `fb349b5f-8000-0080-0010-0000feda0000` | Authentication and security |
| Write | `fb349b5f-8000-0080-0010-0000feda0002` | Commands and transport |
| Device Info | `0000180a-0000-1000-8000-00805f9b34fb` | Standard BLE information |

### Main Characteristics

All pump characteristics follow the prefix `669a0c20-0008-969e-e211-`.

| Function | UUID (suffix) | Properties | Encrypted | Format |
|----------|--------------|------------|-----------|--------|
| Auth Password | `fcbeb2147bc5` | Write | No | MD5 16B |
| Master Version | `fcbeb0147bc5` | Read | No | String |
| System Date | `fcbedc3b7bc5` | Read/Write | Yes | 4B + CRC |
| System Time | `fcbedd3b7bc5` | Read/Write | Yes | 3B + CRC |
| Bolus Start/Stop | `fcbee18b7bc5` | Write | Yes | 13B + CRC |
| Bolus Status | `fcbee28b7bc5` | Read/Notify | Yes | Variable + CRC |
| TBR Start/Stop | `fcbee38b7bc5` | Write | Yes | 16B GLB (no CRC) |
| System Status | `fcbee48b7bc5` | Read/Notify | Yes | 6B + CRC |
| Bolus Notification | `fcbee58b7bc5` | Notify | No | 13B |
| Security Status | `fcbee08b7bc5` | Read | Yes | Variable |
| Setting ID | `fcbeb3147bc5` | Write | Yes | 8B GLB (no CRC) |
| Setting Value | `fcbeb4147bc5` | Read/Write | Yes | 8B GLB (no CRC) |
| Extended Read | `fcff000000ff` | Read | — | Additional frames |
| Pump Key (A) | `fcff0000000a` | Read | No | 64B (32B challenge + 32B pubkey) |
| Pump Key Write (B) | `fcff0000000b` | Write | No | Encrypted server response |

### History

| Type | Count UUID | Index UUID | Value UUID |
|------|-----------|-----------|-----------|
| Events | `fcbecb3b7bc5` | `fcbecc3b7bc5` | `fcbecd3b7bc5` |
| Alerts | `fcbec83b7bc5` | `fcbec93b7bc5` | `fcbeca3b7bc5` |
| System | `86a5a431-...` | `381ddce9-...` | `ae3022af-...` |

### Device Info (standard BLE)

| Function | UUID |
|----------|------|
| Serial | `00002a25-...` |
| Firmware | `00002a26-...` |
| Manufacturer | `00002a29-...` |
| Model | `00002a24-...` |

---

## 3. Authentication

The pump uses authentication based on the **MAC address** of the BLE device, not a user password.

### Algorithm

```
password = MD5(mac_bytes + salt)
```

Where:
- `mac_bytes`: 6 bytes of the BLE device MAC address (e.g.: `EC:2A:F0:02:AF:6F` → `EC 2A F0 02 AF 6F`)
- `salt`: 10-byte constant: `4F C2 45 4D 9B 81 59 A4 93 BB`

### Implementation (Kotlin)

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

### Procedure

1. Connect via BLE to the device (name: `YpsoPump_XXXXXXXX`)
2. Discover GATT services
3. Compute `MD5(mac + salt)` → 16 bytes
4. Write to `CHAR_AUTH_PASSWORD` with `WRITE_TYPE_DEFAULT`

> **Important**: Authentication does NOT require any PIN or passkey from the user.
> Only the MAC address is needed, and it is obtained automatically from the BLE connection.

---

## 4. Encryption (XChaCha20-Poly1305)

After authentication, sensitive commands require end-to-end encryption.

### Algorithm

- **Encryption**: XChaCha20-Poly1305 IETF (NOT NaCl SecretBox/XSalsa20)
- **Nonce**: 24 random bytes
- **AAD**: empty (empty bytes)
- **Tag**: 16 bytes Poly1305 (included in the ciphertext)

### Wire Format

```
┌─────────────────────────────────────┬──────────────┐
│  ciphertext + tag (16 bytes)        │  nonce (24B) │
└─────────────────────────────────────┴──────────────┘
                                        ↑ nonce at the END
```

### Shared Key Derivation

```
shared_secret = X25519(app_private_key, pump_public_key)
shared_key    = HChaCha20(shared_secret, nonce=0x00*16)
```

### Counters in the Plaintext

Before encryption, 12 bytes of counters are appended to the end of the payload:

```
┌──────────────────┬────────────────────┬────────────────────┐
│  payload (N bytes)│ reboot_counter (4B)│ write_counter (8B) │
└──────────────────┴────────────────────┴────────────────────┘
                     LE uint32            LE uint64
```

### XChaCha20 Implementation (Android JCA)

XChaCha20-Poly1305 is not directly available on Android, but is implemented
by combining:
1. **HChaCha20** (pure Kotlin implementation) to derive a 32B subkey
2. **ChaCha20-Poly1305** (available on Android API 28+) with the derived subkey

```kotlin
fun xchacha20Poly1305Encrypt(
    plaintext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray
): ByteArray {
    require(nonce.size == 24)
    require(key.size == 32)

    // HChaCha20: derive subkey from the first 16 bytes of the nonce
    val subkey = hchacha20(key, nonce.copyOfRange(0, 16))

    // Subnonce: 4 zero bytes + last 8 bytes of the original nonce
    val subnonce = ByteArray(12)
    System.arraycopy(nonce, 16, subnonce, 4, 8)

    // Standard ChaCha20-Poly1305 with subkey and subnonce
    val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"),
                IvParameterSpec(subnonce))
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}
```

### HChaCha20 (Pure Kotlin)

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

    // Take positions 0-3 and 12-15 (the constant and nonce positions)
    val output = intArrayOf(state[0],state[1],state[2],state[3],
                            state[12],state[13],state[14],state[15])
    val result = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
    output.forEach { result.putInt(it) }
    return result.array()
}
```

### PumpCryptor Class

Manages the encryption state (shared key, counters):

```kotlin
class PumpCryptor(val sharedKey: ByteArray, private val prefs: SharedPreferences) {
    var readCounter: Long     // Read counter (from the pump)
    var writeCounter: Long    // Write counter (ours)
    var rebootCounter: Int    // Reboot counter (from the pump)

    fun encrypt(payload: ByteArray): ByteArray {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        // Append counters to the plaintext
        val buffer = ByteBuffer.allocate(payload.size + 12).order(LE)
        buffer.put(payload)
        buffer.putInt(rebootCounter)
        writeCounter++
        buffer.putLong(writeCounter)
        // Encrypt
        val ciphertext = xchacha20Poly1305Encrypt(buffer.array(), byteArrayOf(), nonce, sharedKey)
        return ciphertext + nonce  // nonce at the end
    }

    fun decrypt(data: ByteArray): ByteArray {
        val nonce = data.copyOfRange(data.size - 24, data.size)
        val ciphertext = data.copyOfRange(0, data.size - 24)
        val plaintext = xchacha20Poly1305Decrypt(ciphertext, byteArrayOf(), nonce, sharedKey)
        // Extract counters from the last 12 bytes
        val counters = ByteBuffer.wrap(plaintext, plaintext.size - 12, 12).order(LE)
        val peerRebootCounter = counters.int
        val numericCounter = counters.long
        // Synchronize counters
        if (peerRebootCounter != rebootCounter) {
            rebootCounter = peerRebootCounter
            writeCounter = 0
        }
        readCounter = numericCounter
        return plaintext.copyOfRange(0, plaintext.size - 12)  // Without counters
    }
}
```

### Key Persistence

The key is stored in `SharedPreferences("ypso_crypto")`:

| Key | Type | Description |
|-----|------|-------------|
| `shared_key` | String (hex) | 32-byte shared key |
| `shared_key_expires_at` | Long | Expiration timestamp |
| `read_counter` | Long | Last read counter |
| `write_counter` | Long | Last write counter |
| `reboot_counter` | Int | Last reboot counter |

---

## 5. Key Exchange

The key exchange is necessary to establish the shared encryption key.
The key is obtained through a Proregia (relay) server and is valid for approximately
**1 hour** (empirically observed: ~82 minutes). Each exchange generates a different key.

### Complete Flow

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
     │   (challenge,      │                    │
     │    pump_pubkey,    │                    │
     │    app_pubkey,     │                    │
     │    integrity_token)│                    │
     │◄── encrypted_resp │                    │
     │                   │                    │
     │ 4. Write CHAR_CMD_WRITE ──────────────►│
     │   (encrypted_resp) │                    │
     │                   │                    │
     │ 5. Derive key locally:                 │
     │    shared = X25519(app_priv, pump_pub)  │
     │    key = HChaCha20(shared, 0x00*16)     │
     └──────────────────────────────────────────
```

### Pump Public Key

The pump has a **stable** X25519 public key that does not change between reboots:

```
6cfcc0a5a19221c355ade8b7e6bee361121fe2fe6e5506c063bfce8866bfed46
```

### App Key Pair

The app's X25519 key pair is generated once and reused in subsequent exchanges:

```kotlin
fun getOrCreateKeyPair(): X25519KeyPair {
    // Look up in SharedPreferences("ypso_key_exchange")
    val privHex = prefs.getString("x25519_priv_pkcs8", null)
    val pubHex = prefs.getString("x25519_pub_raw", null)
    if (privHex != null && pubHex != null) {
        return loadFromPrefs(privHex, pubHex)
    }
    // Generate new pair
    val keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    saveToPrefs(keyPair)
    return keyPair
}
```

### Shared Key Derivation

```kotlin
fun deriveSharedKey(myPrivateKey: PrivateKey, pumpPublicKeyRaw: ByteArray): ByteArray {
    val peerPubKey = rawBytesToPublicKey(pumpPublicKeyRaw)
    val secret = x25519SharedSecret(myPrivateKey, peerPubKey)  // ECDH
    return hchacha20(secret, ByteArray(16))  // HChaCha20 with nonce=0
}
```

### MAC ↔ Serial

The MAC address can be calculated from the serial number:

```kotlin
fun serialToMac(serial: String): String {
    val num = serial.toLong().let { if (it > 10000000) it - 10000000 else it }
    val hex = "%06X".format(num)
    return "EC:2A:F0:${hex[0..1]}:${hex[2..3]}:${hex[4..5]}"
}
// Example: "10175983" → "EC:2A:F0:02:AF:6F"
```

### Relay Server

The relay server acts as an intermediary between the app and Ypsomed's Proregia server.
It is necessary because the third-party app cannot directly obtain a valid
Play Integrity token for the mylife package.

```
┌──────────┐       ┌──────────────┐       ┌────────────┐
│   App    │       │ Relay Server │       │  Proregia  │
│  (ours)  │       │   (ours)     │       │ (Ypsomed)  │
└────┬─────┘       └──────┬───────┘       └─────┬──────┘
     │                    │                     │
     │ 1. POST /nonce     │                     │
     │   {bt_address}     │                     │
     │   ────────────────►│                     │
     │                    │ 2. getNonce(serial)  │
     │                    │ ────────────────────►│
     │                    │◄──── server_nonce    │
     │◄── server_nonce    │                     │
     │                    │                     │
     │ 3. POST /exchange  │                     │
     │   {challenge,       │                     │
     │    pump_pubkey,     │                     │
     │    app_pubkey,      │                     │
     │    integrity_token} │                     │
     │   ────────────────►│                     │
     │                    │ 4. encryptKey(       │
     │                    │   challenge,         │
     │                    │   pump_pubkey,       │
     │                    │   app_pubkey,        │
     │                    │   integrity_token)   │
     │                    │ ────────────────────►│
     │                    │◄── encrypted_resp    │
     │◄── encrypted_resp  │                     │
     └────────────────────┴─────────────────────┘
```

```kotlin
private fun performKeyExchangeViaRelay(relayUrl: String) {
    // 1. Re-authenticate (the session may have expired)
    pumpManager?.authenticate { authOk ->
        // 2. Read pump public key with retries
        readPumpKeyWithRetry(maxRetries = 5, delay = 1000ms) { pumpKeyData ->
            val challenge = pumpKeyData.copyOfRange(0, 32)
            val pumpPubKey = pumpKeyData.copyOfRange(32, 64)

            // 3. Call the relay
            val response = callRelayServer(relayUrl, challenge, pumpPubKey, appPubKey)

            // 4. Write response to the pump
            pumpManager?.writeMultiFrame(CHAR_CMD_WRITE, response) { ok ->
                // 5. Derive shared key
                val sharedKey = deriveSharedKey(appPrivKey, pumpPubKey)
                val cryptor = PumpCryptor.create(context, sharedKey)
                pumpManager?.pumpCryptor = cryptor
            }
        }
    }
}
```

### Play Integrity Token

The key exchange requires a **Google Play Integrity token** to
authenticate the request with Ypsomed's Proregia server.

#### Token Properties

| Property | Value |
|----------|-------|
| Format | Opaque blob (NOT JWT) |
| Usage | **Single use** (Google anti-replay) |
| TTL | ~1 hour (Firebase App Check) |
| Verification | Server-side by Google Play Services |
| Binding | To the specific `server_nonce` of the request |

#### The Token is NOT JWT

Unlike JWT, the Play Integrity token is an opaque blob generated by
Google Play Services on the device. It cannot be decoded or inspected
from the app. Only Google's server can verify it and extract the verdict
(device integrity, package identity, license).

#### Single Use (Anti-Replay)

Google implements anti-replay protection: each token can only be verified
once on Google's servers. If reuse is attempted, verification fails.
This means that **each key exchange requires a fresh token**.

#### Per-Pump Binding (Nonce Chain)

A Play Integrity token **cannot be reused across different pumps** for
three reasons:

1. **The `server_nonce` is per-pump**: `getNonce(bt_address)` generates a nonce
   specific to the pump's Bluetooth address.
2. **The nonce is embedded in the token**: The `server_nonce` is included as
   the `requestHash` when requesting the Play Integrity token. Google binds it
   to the verdict.
3. **Anti-replay**: Even if the nonce were the same, Google rejects
   token reuse.

```
bt_address → getNonce(serial) → server_nonce
                                     ↓
                         Play Integrity request(
                           requestHash = SHA256(server_nonce)
                         )
                                     ↓
                               token (opaque)
                                     ↓
                         encryptKey(token, challenge, ...)
```

#### Token Acquisition Flow

In our case, the token is obtained on the **relay server** (which runs on a
rooted phone with the mylife app and Frida):

1. **Frida intercepts** the Play Integrity call within the mylife app
2. **Injects the `server_nonce`** as the `requestHash`
3. **Captures the token** generated by Google Play Services
4. **Sends it to Proregia** as `message_attestation_object` in the gRPC request

> **Note**: The relay server is necessary because only the official mylife app
> (with its package name and signing key) can obtain a Play Integrity token
> that Proregia will accept. A third-party app cannot obtain this token directly.

### Shared Key Validity

The shared key derived from the exchange has a **limited validity of
approximately 1 hour** (empirically observed: ~82 minutes).

| Aspect | Detail |
|--------|--------|
| Actual duration | ~1 hour (observed: 82 min) |
| Who invalidates it | The pump (server-side) |
| Expiration indicator | `getSystemStatus()` fails to decrypt |
| Python code (Uneo7) | Assumes 28 days — arbitrary local cache value |
| Each exchange | Generates a **different** key |

#### Expiration Detection

The pump does not communicate when the key expires. The only way to detect it is
to attempt to decrypt an encrypted response and check if it fails:

```kotlin
// Inside withPumpConnection():
val status = suspendGetSystemStatus()
if (status == null && bleManager.lastDecryptFailed) {
    // Dead key → renew immediately, without retries
    throw EncryptionKeyExpiredException()
}
```

#### Automatic Renewal

The SDK automatically renews the key when it detects expiration:

```
1. Command (bolus/TBR/etc) → withPumpConnection()
2. getSystemStatus() → decrypt fails
3. EncryptionKeyExpiredException → withPumpConnectionAndKeyRetry() catches it
4. suspendAutoKeyExchange() → new exchange with relay server
5. Retry the original command with the new key
```

> **Important**: When a decryption failure is detected, renewal happens immediately
> without retrying with the dead key. Retrying with an expired key only
> wastes time (~1.5s per attempt).

---

## 6. Framing Protocol

The ProBluetooth protocol fragments data larger than 19 bytes into multiple BLE frames.

### Frame Format

```
┌────────┬──────────────────────────┐
│ Header │   Data (max 19 bytes)    │
│  (1B)  │                          │
└────────┴──────────────────────────┘
```

**Header**: `((frame_idx + 1) << 4 & 0xF0) | (total_frames & 0x0F)`

- High nibble: current frame number (1-indexed)
- Low nibble: total frames

### Examples

| Case | Header | Meaning |
|------|--------|---------|
| Single frame | `0x11` | Frame 1 of 1 |
| First of 3 | `0x13` | Frame 1 of 3 |
| Second of 3 | `0x23` | Frame 2 of 3 |
| Third of 3 | `0x33` | Frame 3 of 3 |

### Implementation

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

### Multi-Frame Reading

To read data, the first frame is read from the main characteristic.
If `total_frames > 1`, additional frames are read from `CHAR_EXTENDED_READ`:

```kotlin
fun readExtended(firstUuid: UUID, callback: (ByteArray?) -> Unit) {
    readCharacteristic(firstChar).with { _, data ->
        val firstFrame = data.value
        val totalFrames = YpsoFraming.getTotalFrames(firstFrame[0])

        if (totalFrames <= 1) {
            callback(firstFrame.copyOfRange(1, firstFrame.size))
        } else {
            // Read remaining frames from CHAR_EXTENDED_READ
            readRemainingFrames(extChar, totalFrames - 1, frames) { allFrames ->
                callback(YpsoFraming.parseMultiFrameRead(allFrames))
            }
        }
    }
}
```

### Multi-Frame Writing

Frames are written sequentially. Each BLE write must complete
before sending the next one:

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

The pump uses a custom CRC based on the CRC-32 polynomial `0x04C11DB7`
with bitstuffing, returning only the lower 16 bits in Little Endian format.

### Bitstuffing

Before calculating the CRC, the data is reorganized into 4-byte blocks
with reversed byte order:

```
Input:  [A, B, C, D, E, F, G, H]
Output: [D, C, B, A, H, G, F, E]
```

### Implementation

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

### When CRC is Used

| Command | Uses CRC |
|---------|----------|
| Bolus | Yes |
| System Status | Yes (in response) |
| Bolus Status | Yes (in response) |
| Date/Time | Yes |
| TBR | **No** (uses GLB) |
| Settings | **No** (uses GLB) |
| History Count | **No** (uses GLB) |
| History Index | **No** (uses GLB) |
| History Value | Yes (in response) |

---

## 8. GLB Secure Variables

GLB is a self-validating format: a 32-bit value followed by its bitwise
complement. Total: 8 bytes.

```
┌──────────────────┬──────────────────┐
│  value (u32 LE)  │  ~value (u32 LE) │
└──────────────────┴──────────────────┘
```

### Example

For the value `25`:
```
25 in hex: 0x19000000 (LE)
~25:       0xE6FFFFFF (LE)
GLB(25):   19 00 00 00 E6 FF FF FF
```

### Implementation

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

### GLB Uses

- **Settings**: Both the index and the value are encoded in GLB
- **TBR**: Two consecutive GLB values (percentage + duration)
- **History**: Count and Index are encoded in GLB

---

## 9. Counter Synchronization

Encryption uses counters (`reboot_counter` + `write_counter`) that must be
synchronized between the app and the pump. If the counters don't match, the pump
rejects encrypted commands.

### Problem

When a new key is established, the app doesn't know the pump's current
`reboot_counter`. If it sends a write with `reboot_counter = 0` and the pump
expects a different value, the command will be rejected.

### Solution: Auto-sync

Before each encrypted write, it verifies that the counters are synchronized.
If they're not, it performs a system status read (which is a read operation,
not a write) to learn the counters:

```kotlin
private var _countersSynced = true

var pumpCryptor: PumpCryptor? = null
    set(value) {
        field = value
        _countersSynced = (value == null)  // new key = needs sync
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

### Flow

```
1. New key is assigned → _countersSynced = false
2. App attempts to send bolus/TBR/etc
3. ensureCountersSynced() detects it's not synchronized
4. Reads System Status → decrypt learns reboot_counter from the pump
5. _countersSynced = true
6. Proceeds with the encrypted write (with the correct reboot_counter)
```

---

## 10. Pump Commands

### 10.1 System Status

Reads the current pump status: delivery mode, remaining insulin, battery.

**Characteristic**: `CHAR_SYSTEM_STATUS`
**Pipeline**: Read → Decrypt → CRC check → Parse

**Payload format** (6 bytes without CRC):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 1 | Delivery mode |
| 1 | 4 | Remaining insulin (u32 LE, hundredths of a unit) |
| 5 | 1 | Battery (percentage, 0-100) |

**Delivery modes**:

| Value | Meaning |
|-------|---------|
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

**Characteristic**: `CHAR_BOLUS_START_STOP`
**Pipeline**: Payload(13B) → CRC → Encrypt → Frame → Write

**Payload format** (13 bytes):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 4 | Total units × 100 (u32 LE) |
| 4 | 4 | Duration in minutes (u32 LE, 0 = fast bolus) |
| 8 | 4 | Immediate units × 100 (u32 LE, for combo) |
| 12 | 1 | Type: 1 = fast, 2 = extended |

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

**Cancel bolus**: 13 zero bytes with the last byte = bolus type:

```kotlin
fun cancelBolus(kind: String = "fast", callback: (Boolean) -> Unit) {
    val bolusType = if (kind == "fast") 1 else 2
    val payload = ByteArray(13).also { it[12] = bolusType.toByte() }
    sendBolusCommand(payload, callback)
}
```

**Bolus status**: Read from `CHAR_BOLUS_STATUS` (encrypted, with CRC).

**Bolus notifications**: `CHAR_BOLUS_NOTIFICATION` (13B, NOT encrypted):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 1 | Fast bolus status (0=idle, 1=delivering, 3=cancelled, 4=completed) |
| 1 | 4 | Fast bolus sequence |
| 5 | 1 | Extended bolus status |
| 6 | 4 | Extended bolus sequence |

### 10.3 TBR (Temporary Basal Rate)

**Characteristic**: `CHAR_TBR_START_STOP`
**Pipeline**: GLB(16B) → Encrypt → Frame → Write (NO CRC)

**Format**: Two consecutive GLB variables, without CRC:

```
┌──────────────────────────────┬──────────────────────────────┐
│  GLB(percentage) — 8 bytes   │  GLB(duration_min) — 8 bytes │
└──────────────────────────────┴──────────────────────────────┘
```

> **VERY IMPORTANT**: The percentage is the RAW value (25 for 25%), NOT centi-percentage.
> Using centi-percentage (2500) causes error 130 (0x82 — value rejected by the pump).
> Using payload formats other than 16B causes error 138 (0x8A — unrecognized format).

```kotlin
fun startTbr(percent: Int, durationMinutes: Int, callback: (Boolean) -> Unit) {
    val payload = ByteBuffer.allocate(16).order(LE)
        .putInt(percent)                 // raw: 25 for 25%
        .putInt(percent.inv())           // GLB complement
        .putInt(durationMinutes)         // minutes: 30
        .putInt(durationMinutes.inv())   // GLB complement
        .array()

    ensureCountersSynced {
        val encrypted = encryptIfNeeded(payload)
        val frames = YpsoFraming.chunkPayload(encrypted)
        writeFramesSequentially(charTbrStartStop, frames, 0, callback)
    }
}
```

**Cancel TBR**: Send 100% with 0 minutes:

```kotlin
fun cancelTbr(callback: (Boolean) -> Unit) {
    val payload = ByteBuffer.allocate(16).order(LE)
        .putInt(100).putInt(100.inv())    // 100% = normal rate
        .putInt(0).putInt(0.inv())        // 0 minutes
        .array()
    // ... encrypt → frame → write
}
```

**Valid parameters**:
- Percentage: 0-200 (0 = suspend, 100 = normal, 200 = double)
- Duration: 15-1440 minutes (multiples of 15)

### 10.4 Basal Profiles

The pump has two basal profile programs: **A** and **B**, each with 24 hourly
rates.

**Characteristics**: `CHAR_SETTING_ID` + `CHAR_SETTING_VALUE`
**Pipeline**: GLB → Encrypt → Frame → Write/Read (NO CRC)

**Settings indices**:

| Program | Rate 00:00 | Rate 23:00 | Program value |
|---------|-----------|-----------|---------------|
| A | 14 | 37 | 3 |
| B | 38 | 61 | 10 |

**Active program**: Setting index = 1

**Read an hourly rate**:

```
1. Write GLB(index) → CHAR_SETTING_ID    [encrypt → frame]
2. Read CHAR_SETTING_VALUE                [decrypt → GLB decode]
3. Value = hundredths of U/h (divide by 100)
```

**Write an hourly rate**:

```
1. Write GLB(index) → CHAR_SETTING_ID    [encrypt → frame]
2. Write GLB(value) → CHAR_SETTING_VALUE  [encrypt → frame]
   where value = rate_U_h × 100 (integer)
```

**Read complete profile (24 rates)**:

```kotlin
fun readBasalProfile(program: Char, callback: (List<Float>?) -> Unit) {
    val startIndex = when (program) {
        'A' -> SettingsIndex.PROGRAM_A_START  // 14
        'B' -> SettingsIndex.PROGRAM_B_START  // 38
        else -> return
    }
    // Read sequentially indices startIndex..startIndex+23
    readBasalRateSequential(startIndex, startIndex + 23, rates, callback)
}
```

**Write complete profile**:

```kotlin
fun writeBasalProfile(program: Char, rates: List<Float>, callback: (Boolean) -> Unit) {
    val startIndex = when (program) {
        'A' -> SettingsIndex.PROGRAM_A_START
        'B' -> SettingsIndex.PROGRAM_B_START
        else -> return
    }
    // Write sequentially 24 settings
    rates.forEachIndexed { hour, rate ->
        writeSetting(startIndex + hour, (rate * 100).toInt()) { success ->
            // Continue with the next one or report error
        }
    }
}
```

**Activate a program**:

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

**Read active program**:

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

### 10.5 Time Synchronization

**Characteristics**: `CHAR_SYSTEM_DATE` + `CHAR_SYSTEM_TIME`
**Pipeline**: Payload → CRC → Encrypt → Frame → Write

**Date** (4 bytes):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 2 | Year (u16 LE) |
| 2 | 1 | Month (1-12) |
| 3 | 1 | Day (1-31) |

**Time** (3 bytes):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 1 | Hour (0-23) |
| 1 | 1 | Minute (0-59) |
| 2 | 1 | Second (0-59) |

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
        // Write date with CRC + encryption
        val dateWithCrc = encryptIfNeeded(YpsoCrc.appendCrc(datePayload))
        writeFrames(dateChar, dateWithCrc) { dateOk ->
            // Write time with CRC + encryption
            val timeWithCrc = encryptIfNeeded(YpsoCrc.appendCrc(timePayload))
            writeFrames(timeChar, timeWithCrc) { timeOk ->
                callback(timeOk)
            }
        }
    }
}
```

### 10.6 History

The pump stores three types of history: events, alerts, and system.

**Reading flow**:

```
1. Read COUNT        → GLB → decrypt → get total entries
2. Write INDEX       → GLB → encrypt → select entry N
3. Read VALUE        → decrypt → CRC check → parse HistoryEntry
4. Repeat 2-3 for each entry
```

**History entry format** (17 bytes):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 4 | Unix timestamp (u32 LE) |
| 4 | 1 | Event type |
| 5 | 2 | Value 1 (u16 LE) |
| 7 | 2 | Value 2 (u16 LE) |
| 9 | 2 | Value 3 (u16 LE) |
| 11 | 4 | Sequence (u32 LE) |
| 15 | 2 | Index (u16 LE) |

**Main event types**:

| ID | Event | Values |
|----|-------|--------|
| 1 | Fast bolus started | v1=hundredths_U |
| 2 | Fast bolus completed | v1=hundredths_U |
| 3 | Fast bolus cancelled | v1=delivered, v2=requested |
| 6 | Basal rate changed | v1=new_rate |
| 7 | Basal program changed | v1=program |
| 8 | TBR started | v1=percentage, v2=duration |
| 9 | TBR completed | |
| 10 | TBR cancelled | |
| 12 | Cartridge changed | |
| 13 | Battery changed | |
| 14 | Date/time set | |
| 100 | Low reservoir | |
| 101 | Low battery | |
| 102 | Occlusion | |

---

## 11. Complete Connection Flow

The automatic connection flow (`autoConnect`) follows these steps:

```
┌────────────────────────────────────────────────────┐
│ 1. BLE SCAN                                        │
│    - Search for device "YpsoPump_XXXXXXXX"         │
│    - Timeout: 15 seconds                           │
├────────────────────────────────────────────────────┤
│ 2. CONNECTION                                      │
│    - connect(device)                               │
│    - Discover GATT services                        │
│    - Map characteristics                           │
│    - Enable notifications (Bolus, Status)          │
├────────────────────────────────────────────────────┤
│ 3. AUTHENTICATION                                  │
│    - Compute MD5(mac + salt)                       │
│    - Write to CHAR_AUTH_PASSWORD                    │
├────────────────────────────────────────────────────┤
│ 4. LOAD ENCRYPTION KEY                             │
│    - Look up in SharedPreferences("ypso_crypto")   │
│    - If exists: assign PumpCryptor                 │
│    - If not: → step 4b (key exchange)              │
├────────────────────────────────────────────────────┤
│ 4b. KEY EXCHANGE (if needed)                       │
│    - If saved relay URL exists: auto key exchange  │
│    - If not: show Key Exchange UI to user          │
├────────────────────────────────────────────────────┤
│ 5. VALIDATE KEY                                    │
│    - Read System Status (decrypt + CRC check)      │
│    - If fails: → step 4b (invalid key)             │
│    - If success: counters synchronized             │
├────────────────────────────────────────────────────┤
│ 6. POST-CONNECTION INITIALIZATION                  │
│    - Synchronize time                              │
│    - Read active program                           │
│    - Update UI with pump status                    │
│    - Mark as "Authenticated + Encrypted"           │
└────────────────────────────────────────────────────┘
```

### Simplified Code

```kotlin
fun autoConnect(context: Context) {
    _isAutoConnecting.value = true

    // 1. Scan
    startScan()  // BLE scan with filter "YpsoPump_"

    // 2. When found → connect
    pumpManager?.connect(device)?.enqueue()

    // 3. Authenticate
    pumpManager?.authenticate { authOk ->
        if (!authOk) { error("Auth failed"); return }

        // 4. Load key
        val cryptor = YpsoKeyExchange(context, pumpManager).loadCryptor()
        if (cryptor != null) {
            pumpManager?.pumpCryptor = cryptor
            // 5. Validate
            pumpManager?.getSystemStatus { status ->
                if (status != null) {
                    // 6. Success
                    onKeyValidated(status)
                } else {
                    // Invalid key → auto-recovery
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

## 12. Automatic Key Recovery

When the encryption key expires or is invalidated (for example, when pairing
with another app), the system attempts to recover it automatically.

### Flow

```
1. getSystemStatus() fails with BAD_DECRYPT
2. pumpCryptor is invalidated (= null)
3. autoRecoverKey() is called
4. If a saved relay URL exists in SharedPreferences:
   a. Re-authenticate
   b. Read pump public key
   c. Call the relay server
   d. Write response to the pump
   e. Derive new shared key
   f. Validate with getSystemStatus()
   g. Continue with normal flow
5. If no relay URL exists:
   a. Show Key Exchange card to the user
   b. The user enters the URL and presses the button
```

### Relay URL Persistence

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

The URL is automatically saved when the user performs a successful manual key exchange.
In subsequent connections, if the key fails, the saved URL is used
to attempt an automatic renewal without user intervention.

---

## 13. Project Structure

```
app/src/main/java/com/ypsopump/controller/
├── MainActivity.kt              # Main Activity, UI wiring
├── model/
│   ├── ConnectionState.kt       # Sealed class of connection states
│   └── PumpStatus.kt            # Data class for status UI
├── ui/
│   ├── MainViewModel.kt         # Central ViewModel, orchestrates commands
│   └── DiscoveredDevice.kt      # Discovered BLE devices
└── ble/
    ├── YpsoPumpManager.kt       # BLE manager (Nordic BleManager)
    ├── YpsoCrypto.kt            # X25519, HChaCha20, XChaCha20-Poly1305, PumpCryptor
    ├── YpsoKeyExchange.kt       # Key exchange with Proregia/relay
    ├── YpsoFraming.kt           # ProBluetooth multi-frame protocol
    ├── YpsoCrc.kt               # Custom CRC16
    ├── YpsoCommand.kt           # Constants, GLB, data classes
    ├── YpsoPumpUuids.kt         # Service and characteristic UUIDs
    └── ProregiaClient.kt        # gRPC client for Proregia server
```

### Key Dependencies

```groovy
// BLE
implementation "no.nordicsemi.android:ble:2.7.4"

// Crypto (native Android API 28+ for ChaCha20-Poly1305)
// X25519 native Android API 33+
// No libsodium needed

// gRPC (for Proregia communication)
implementation "io.grpc:grpc-okhttp:..."
implementation "io.grpc:grpc-protobuf-lite:..."
implementation "io.grpc:grpc-stub:..."
```

---

## 14. Quick Format Reference

### Pipeline Summary by Command

| Command | Char | Payload | +CRC | GLB | Pipeline |
|---------|------|---------|------|-----|----------|
| **Bolus Start** | `e18b` | 13B | Yes | No | payload → CRC → encrypt → frame → write |
| **Bolus Cancel** | `e18b` | 13B | Yes | No | payload → CRC → encrypt → frame → write |
| **TBR Start** | `e38b` | 16B | No | Yes (×2) | GLB×2 → encrypt → frame → write |
| **TBR Cancel** | `e38b` | 16B | No | Yes (×2) | GLB×2 → encrypt → frame → write |
| **System Status** | `e48b` | 6B resp | Yes | No | read → decrypt → CRC → parse |
| **Bolus Status** | `e28b` | Variable | Yes | No | read → decrypt → CRC → parse |
| **Setting Read** | `b314`/`b414` | 8B | No | Yes | GLB(idx) → enc → write; read → dec → GLB |
| **Setting Write** | `b314`/`b414` | 8B | No | Yes | GLB(idx) → enc → write; GLB(val) → enc → write |
| **Sync Date** | `dc3b` | 4B | Yes | No | payload → CRC → encrypt → frame → write |
| **Sync Time** | `dd3b` | 3B | Yes | No | payload → CRC → encrypt → frame → write |
| **History Count** | Various | 8B resp | No | Yes | read → decrypt → GLB decode |
| **History Index** | Various | 8B | No | Yes | GLB(idx) → encrypt → frame → write |
| **History Value** | Various | 17B resp | Yes | No | read → decrypt → CRC → parse |
| **Auth** | `b214` | 16B | No | No | MD5(mac+salt) → write (no encryption) |

### Known BLE Errors

| Code | Hex | Meaning |
|------|-----|---------|
| 130 | 0x82 | Format recognized, values rejected |
| 133 | 0x85 | Generic GATT error (connection lost) |
| 138 | 0x8A | Unrecognized format/length |
| 139 | 0x8B | Resource busy or write timeout |

### Test Pump Data

| Field | Value |
|-------|-------|
| Serial | 10175983 |
| MAC | EC:2A:F0:02:AF:6F |
| Firmware | V05.02.03 |
| Pump PubKey | `6cfcc0a5a19221c355ade8b7e6bee361121fe2fe6e5506c063bfce8866bfed46` |

---

## Additional Notes

### Known Limitations

1. **X25519 requires API 33+**: Native X25519 key generation on Android
   is only available from Android 13 (API 33). For earlier versions,
   Bouncy Castle or libsodium would be needed.

2. **ChaCha20-Poly1305 requires API 28+**: Available from Android 9.

3. **Key validity**: The shared key is valid for approximately
   **1 hour** (~82 min observed). The pump invalidates it by time, not by
   pairing with another app. The SDK detects expiration when decryption
   fails and renews automatically if a relay URL is configured.

4. **Byte order**: The entire protocol uses **Little Endian** except for hashes
   (MD5 and standard BLE headers which use Big Endian).

### Reference Code

- **Python (Uneo7/Ypso)**: `/Users/victor/Developer/CamAPS/Ypso-main/`
  - `pump/crypto.py` — Encryption and key derivation
  - `pump/sdk.py` — Pump commands
  - `pump/utils.py` — Framing and utilities
  - `pump/crc.py` — CRC16

- **Pairing scripts**: `~/Ypso/pairing.py`
