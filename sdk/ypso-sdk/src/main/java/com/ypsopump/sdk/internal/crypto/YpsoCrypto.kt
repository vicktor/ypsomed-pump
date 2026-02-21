package com.ypsopump.sdk.internal.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Operaciones criptográficas para el protocolo YpsoPump.
 *
 * Utiliza la JCA de Android de forma nativa:
 * - **X25519** para el intercambio de claves Diffie-Hellman (requiere API 33+).
 * - **ChaCha20-Poly1305** para el cifrado autenticado (requiere API 28+).
 * - **HChaCha20** implementado en Kotlin puro para la derivación de subclave.
 *
 * Flujo criptográfico completo del protocolo:
 * 1. Generar par de claves X25519 del dispositivo.
 * 2. Leer la clave pública de la bomba (32 bytes) vía BLE.
 * 3. Calcular el secreto compartido: `X25519(privApp, pubBomba)`.
 * 4. Derivar la clave compartida: `HChaCha20(secreto, nonce_cero_16B)`.
 * 5. Cifrar/descifrar mensajes con XChaCha20-Poly1305 usando esa clave.
 *
 * Formato en cable: `ciphertext+tag(16B) || nonce(24B)` — el nonce va AL FINAL.
 */
internal object YpsoCrypto {

    private val X25519_PUBKEY_DER_HEADER = byteArrayOf(
        0x30, 0x2A,
        0x30, 0x05,
        0x06, 0x03, 0x2B, 0x65, 0x6E,
        0x03, 0x21, 0x00
    )

    // ==================== X25519 Key Exchange ====================

    data class X25519KeyPair(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val rawPublicKey: ByteArray
    )

    /**
     * Genera un nuevo par de claves X25519 para el intercambio Diffie-Hellman.
     *
     * Utiliza [KeyPairGenerator] de la JCA con el algoritmo "X25519" (requiere API 33+).
     * La clave pública raw de 32 bytes se extrae del formato DER/X.509 codificado.
     *
     * @return Un [X25519KeyPair] con la clave privada JCA, la clave pública JCA
     *         y los 32 bytes raw de la clave pública.
     */
    fun generateX25519KeyPair(): X25519KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val keyPair = kpg.generateKeyPair()
        val rawPub = publicKeyToRawBytes(keyPair.public)
        return X25519KeyPair(keyPair.private, keyPair.public, rawPub)
    }

    fun publicKeyToRawBytes(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    fun rawBytesToPublicKey(rawBytes: ByteArray): PublicKey {
        require(rawBytes.size == 32) { "X25519 public key must be 32 bytes" }
        val derEncoded = X25519_PUBKEY_DER_HEADER + rawBytes
        val keyFactory = KeyFactory.getInstance("X25519")
        return keyFactory.generatePublic(X509EncodedKeySpec(derEncoded))
    }

    fun x25519SharedSecret(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(myPrivateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    /**
     * Deriva la clave compartida a partir de la clave privada del dispositivo y la clave
     * pública raw de 32 bytes de la bomba.
     *
     * Proceso:
     * 1. Convierte la clave pública raw de la bomba a objeto [PublicKey] JCA.
     * 2. Calcula el secreto Diffie-Hellman: `X25519(privApp, pubBomba)`.
     * 3. Aplica HChaCha20 con un nonce de 16 bytes a cero para derivar la clave final.
     *
     * La clave resultante de 32 bytes es la que se usa en todos los mensajes cifrados
     * subsiguientes con [xchacha20Poly1305Encrypt] / [xchacha20Poly1305Decrypt].
     *
     * @param myPrivateKey Clave privada X25519 del dispositivo.
     * @param pumpPublicKeyRaw 32 bytes de la clave pública X25519 de la bomba.
     * @return 32 bytes de clave compartida derivada.
     */
    fun deriveSharedKey(myPrivateKey: PrivateKey, pumpPublicKeyRaw: ByteArray): ByteArray {
        val peerPubKey = rawBytesToPublicKey(pumpPublicKeyRaw)
        val secret = x25519SharedSecret(myPrivateKey, peerPubKey)
        return hchacha20(secret, ByteArray(16))
    }

    // ==================== HChaCha20 (pure Kotlin) ====================

    private fun rotl32(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] = state[a] + state[b]; state[d] = rotl32(state[d] xor state[a], 16)
        state[c] = state[c] + state[d]; state[b] = rotl32(state[b] xor state[c], 12)
        state[a] = state[a] + state[b]; state[d] = rotl32(state[d] xor state[a], 8)
        state[c] = state[c] + state[d]; state[b] = rotl32(state[b] xor state[c], 7)
    }

    /**
     * Implementación pura en Kotlin del algoritmo HChaCha20.
     *
     * HChaCha20 es una función de derivación de subclave que forma parte del
     * esquema XChaCha20. Dado una clave de 32 bytes y un nonce de 16 bytes,
     * produce una subclave de 32 bytes ejecutando 20 rondas de ChaCha20
     * y extrayendo las palabras de estado 0-3 y 12-15 (omitiendo las de contador).
     *
     * Se usa en dos contextos dentro del SDK:
     * - **Derivación de clave compartida**: `HChaCha20(X25519_secret, zeros_16B)`.
     * - **Subclave XChaCha20**: `HChaCha20(key, nonce[0..15])` antes de cifrar/descifrar.
     *
     * @param key Clave de 32 bytes.
     * @param nonce Nonce de 16 bytes.
     * @param constant Constante de 16 bytes (por defecto "expand 32-byte k").
     * @return Subclave derivada de 32 bytes.
     * @throws IllegalArgumentException Si las longitudes de [key], [nonce] o [constant] son incorrectas.
     */
    fun hchacha20(
        key: ByteArray,
        nonce: ByteArray,
        constant: ByteArray = "expand 32-byte k".toByteArray(Charsets.US_ASCII)
    ): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 16) { "Nonce must be 16 bytes" }
        require(constant.size == 16) { "Constant must be 16 bytes" }

        val buf = ByteBuffer.wrap(constant + key + nonce).order(ByteOrder.LITTLE_ENDIAN)
        val state = IntArray(16) { buf.int }

        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        val output = intArrayOf(
            state[0], state[1], state[2], state[3],
            state[12], state[13], state[14], state[15]
        )
        val result = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        output.forEach { result.putInt(it) }
        return result.array()
    }

    // ==================== XChaCha20-Poly1305 ====================

    /**
     * Cifra un mensaje con XChaCha20-Poly1305 (modo IETF).
     *
     * Proceso interno:
     * 1. Deriva una subclave de 32 bytes con `HChaCha20(key, nonce[0..15])`.
     * 2. Construye un subnonce de 12 bytes: `0x00000000 || nonce[16..23]`.
     * 3. Cifra con `ChaCha20/Poly1305/NoPadding` de la JCA de Android.
     *
     * El resultado incluye el texto cifrado seguido del tag de autenticación de 16 bytes.
     * El nonce de 24 bytes **no** se incluye en la salida; el llamador debe añadirlo
     * según el formato en cable del protocolo (`ciphertext+tag || nonce`).
     *
     * @param plaintext Datos a cifrar.
     * @param aad Datos adicionales autenticados (AAD). Usar `byteArrayOf()` si está vacío.
     * @param nonce Nonce aleatorio de 24 bytes.
     * @param key Clave compartida de 32 bytes.
     * @return Array con `ciphertext + tag(16B)`.
     * @throws IllegalArgumentException Si [nonce] no tiene 24 bytes o [key] no tiene 32 bytes.
     */
    fun xchacha20Poly1305Encrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(nonce.size == 24) { "XChaCha20 nonce must be 24 bytes" }
        require(key.size == 32) { "Key must be 32 bytes" }

        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        val subnonce = ByteArray(12)
        System.arraycopy(nonce, 16, subnonce, 4, 8)

        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(subnonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Descifra y verifica un mensaje cifrado con XChaCha20-Poly1305 (modo IETF).
     *
     * El proceso es el inverso de [xchacha20Poly1305Encrypt]:
     * 1. Deriva la subclave con `HChaCha20(key, nonce[0..15])`.
     * 2. Construye el subnonce de 12 bytes: `0x00000000 || nonce[16..23]`.
     * 3. Descifra y verifica el tag con `ChaCha20/Poly1305/NoPadding` de la JCA.
     *
     * El parámetro [ciphertext] debe incluir el tag de autenticación de 16 bytes al final
     * (es decir, `ciphertext_raw + tag`), tal como lo devuelve [xchacha20Poly1305Encrypt].
     *
     * Lanza una excepción de la JCA si el tag de autenticación no es válido (posible
     * corrupción o clave incorrecta).
     *
     * @param ciphertext Texto cifrado con tag de autenticación de 16 bytes al final.
     * @param aad Datos adicionales autenticados (AAD). Usar `byteArrayOf()` si está vacío.
     * @param nonce Nonce de 24 bytes utilizado durante el cifrado.
     * @param key Clave compartida de 32 bytes.
     * @return Plaintext descifrado.
     * @throws IllegalArgumentException Si [nonce] no tiene 24 bytes o [key] no tiene 32 bytes.
     * @throws javax.crypto.AEADBadTagException Si el tag de autenticación no es válido.
     */
    fun xchacha20Poly1305Decrypt(
        ciphertext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(nonce.size == 24) { "XChaCha20 nonce must be 24 bytes" }
        require(key.size == 32) { "Key must be 32 bytes" }

        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        val subnonce = ByteArray(12)
        System.arraycopy(nonce, 16, subnonce, 4, 8)

        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(subnonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // ==================== Serial / Address Helpers ====================

    /**
     * Convierte el número de serie de la bomba en los 6 bytes de su dirección Bluetooth.
     *
     * La dirección BT de YpsoPump tiene el formato `EC:2A:F0:XX:XX:XX`, donde los
     * 3 bytes finales se derivan del número de serie módulo 10.000.000 en little-endian
     * (bytes 2, 1 y 0 del valor de 4 bytes LE, en ese orden).
     *
     * @param serial Número de serie de la bomba como cadena decimal (p. ej. "10175983").
     * @return Array de 6 bytes con la dirección BT en formato de escritura BLE.
     */
    fun serialToBtAddress(serial: String): ByteArray {
        val num = serial.toLong() % 10000000
        val little = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(num.toInt()).array()
        return byteArrayOf(0xEC.toByte(), 0x2A, 0xF0.toByte(), little[2], little[1], little[0])
    }

    /**
     * Convierte el número de serie de la bomba en una cadena de dirección MAC legible.
     *
     * Produce una cadena en formato `EC:2A:F0:XX:XX:XX` donde los últimos 3 octetos
     * se calculan a partir del número de serie (si supera 10.000.000 se resta ese valor).
     *
     * @param serial Número de serie de la bomba como cadena decimal.
     * @return Cadena con la dirección MAC en formato `XX:XX:XX:XX:XX:XX`.
     */
    fun serialToMac(serial: String): String {
        val num = serial.toLong().let { if (it > 10000000) it - 10000000 else it }
        val hex = "%06X".format(num)
        return "EC:2A:F0:${hex.substring(0, 2)}:${hex.substring(2, 4)}:${hex.substring(4, 6)}"
    }
}
