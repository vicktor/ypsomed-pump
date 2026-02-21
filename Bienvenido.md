# 🩸 YpsoPump DIY — Cómo hemos conseguido hablar con la bomba de insulina YpsoPump

**Un proyecto abierto para controlar tu YpsoPump desde tu propio código.**

> 🧑‍🤝‍🧑 Este documento está pensado para que cualquiera lo entienda, seas developer o no. Si algo no queda claro, abre un issue y lo mejoramos entre todos.

---

## ¿Qué es esto?

Si llevas una **YpsoPump** sabes que solo puedes controlarla desde la app oficial **mylife**. Punto. No hay API pública, no hay documentación, no hay forma oficial de que una app de terceros (como AndroidAPS, Loop, o cualquier proyecto DIY) le diga a la bomba "oye, pon un bolo de 2 unidades".

Hasta ahora.

Hemos hecho ingeniería inversa del protocolo Bluetooth (BLE) que usa la YpsoPump y hemos documentado **todo**: cómo se conecta, cómo se autentica, cómo se cifran los comandos, cómo se envían bolos, basales temporales, cómo se lee el historial... todo.

El objetivo es que este conocimiento sea público y que la comunidad pueda construir encima: drivers para AndroidAPS, apps propias, herramientas de monitorización, lo que se os ocurra.

---

## ¿Cómo funciona la comunicación con la bomba? (Versión sencilla)

Imagina que la bomba es una caja fuerte con tres cerraduras. Para darle cualquier orden, tienes que abrirlas en orden:

### 🔓 Cerradura 1: Conexión Bluetooth

La bomba aparece como un dispositivo Bluetooth normal con un nombre tipo `YpsoPump_10175983`. Tu teléfono la ve, se conecta, y ya tienes el "cable" virtual entre los dos.

### 🔓 Cerradura 2: Autenticación (la contraseña es la dirección MAC)

Aquí viene lo curioso: la "contraseña" de la bomba no es un PIN que tú elijas. Es simplemente un hash MD5 de la dirección MAC del Bluetooth de la bomba + una salt fija. Es decir:

```
contraseña = MD5(dirección_MAC_de_la_bomba + salt_secreta)
```

La dirección MAC la obtienes automáticamente al conectarte por Bluetooth y la sal es siempre la misma (la hemos sacado de la app oficial). Así que básicamente, si puedes ver la bomba por Bluetooth, puedes autenticarte. No hay PIN, no hay passkey, no hay nada que el usuario tenga que escribir.

### 🔓 Cerradura 3: Cifrado (aquí viene lo gordo)

Una vez autenticado, para enviar comandos sensibles (bolos, basales, etc.) todo va cifrado con **XChaCha20-Poly1305** — un cifrado militar, literalmente. Cada comando que envías va cifrado con una clave compartida entre tu app y la bomba.

Y aquí está **el gran problema**: ¿cómo consigues esa clave?

---

## 🔑 El problema de las claves (y cómo lo hemos resuelto)

### El muro: Play Integrity

Ypsomed (la empresa que hace la bomba) usa un servidor llamado **Proregia** para gestionar las claves de cifrado. Cuando la app oficial necesita una clave nueva, hace esto:

1. Le pide un "desafío" a la bomba
2. Le pide al servidor de Ypsomed que genere la clave
3. Pero para que el servidor acepte la petición... necesita un **token de Google Play Integrity**

Y ese token es la pared. Google Play Integrity es un sistema que le dice al servidor: "te confirmo que esta petición viene de la app oficial **mylife**, instalada en un teléfono legítimo, sin root, sin modificar". Si tú haces tu propia app, Google no te va a dar ese token para el paquete de mylife. Fin del juego.

¿O no?

### La solución: un Relay Server (el "hombre en el medio")

Lo que hemos montado es un **servidor relay** — básicamente un intermediario que sí puede conseguir ese token. ¿Cómo?

```
Tu App  ──────►  Relay Server  ──────►  Servidor de Ypsomed (Proregia)
  │                   │                          │
  │  "Necesito        │  "Aquí tienes el         │
  │   una clave       │   token de Play          │
  │   para mi bomba"  │   Integrity válido       │
  │                   │   + los datos de la      │
  │                   │   bomba"                 │
  │                   │                          │
  │◄──────────────────│◄─────────────────────────│
  │   "Aquí tienes    │   "Aquí tienes la        │
  │    la respuesta   │    respuesta cifrada"    │
  │    cifrada"       │                          │
```

El relay server es un teléfono Android (con root) que tiene instalada la app oficial **mylife**. Con **Frida** (una herramienta de instrumentación), interceptamos la función de Play Integrity dentro de la app oficial y le decimos: "oye, en vez de pedir el token para tu propio nonce, pídelo para ESTE nonce que te paso yo". Google genera el token (porque la app oficial es legítima), nosotros lo capturamos y se lo pasamos al servidor de Ypsomed.

**En resumen**: el relay server es un puente que usa la app oficial como "llave maestra" para conseguir tokens válidos.

### ¿Cada cuánto hay que hacer esto?

La clave dura **aproximadamente 1 hora** (hemos medido unos 82 minutos). Después de eso, la bomba deja de aceptar tus comandos cifrados y necesitas una clave nueva. El SDK que hemos hecho detecta automáticamente cuándo la clave ha caducado y pide una nueva al relay sin que tú hagas nada.

### ¿Es un solo uso?

Sí. Cada token de Play Integrity es de **un solo uso** (anti-replay de Google). Cada vez que necesitas una clave nueva, necesitas un token nuevo. Y cada token está vinculado a UNA bomba concreta (por su dirección Bluetooth), así que no puedes reutilizar tokens entre bombas.

---

## 📡 ¿Qué puedes hacer una vez que tienes la clave?

Con la conexión establecida y la clave en mano, puedes:

| Acción | ¿Funciona? | Notas |
|--------|:----------:|-------|
| Leer estado de la bomba | ✅ | Modo actual, insulina restante, batería |
| Enviar un bolo rápido | ✅ | Hasta 25 unidades |
| Enviar un bolo extendido | ✅ | Con duración configurable |
| Cancelar un bolo | ✅ | |
| Iniciar basal temporal (TBR) | ✅ | 0-200%, en pasos de 15 min |
| Cancelar basal temporal | ✅ | |
| Leer/escribir perfiles basales | ✅ | Programas A y B, 24 tasas horarias |
| Cambiar programa basal activo | ✅ | A ↔ B |
| Sincronizar fecha y hora | ✅ | |
| Leer historial de eventos | ✅ | Bolos, basales, alertas, cambios de cartucho... |
| Leer notificaciones de bolo | ✅ | En tiempo real vía BLE notify |

Básicamente, **todo lo que hace la app oficial, lo podemos hacer nosotros**.

---

## 🏗️ Arquitectura: cómo encajan las piezas

```
┌─────────────────────────────────────────────────────┐
│                       TU APP                        │
│                                                     │
│  Envía comandos de alto nivel:                      │
│  "pon un bolo de 1.5U", "TBR al 50% 30m in"         │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│                   SDK (nuestro código)              │
│                                                     │
│  Se encarga de todo el trabajo sucio:               │
│  • Conectar por BLE                                 │
│  • Autenticar con MD5(MAC + sal)                    │
│  • Cifrar/descifrar con XChaCha20-Poly1305          │
│  • Fragmentar en tramas BLE (máx 19 bytes)          │
│  • Gestionar contadores de cifrado                  │
│  • Renovar claves automáticamente vía relay         │
│  • Calcular CRC16 / codificar GLB                   │
└──────────────────────┬──────────────────────────────┘
                       │ BLE
                       ▼
┌─────────────────────────────────────────────────────┐
│                    YPSOPUMP                         │
│                                                     │
│  Recibe comandos cifrados, los ejecuta              │
│  y responde con datos cifrados                      │
└─────────────────────────────────────────────────────┘

                    ╔═══════════════╗
                    ║ RELAY SERVER  ║  (solo para obtener claves,
                    ║ (teléfono     ║   no para comandos normales)
                    ║  con Frida)   ║
                    ╚═══════════════╝
```

**Importante**: el relay server solo se necesita para obtener las claves de cifrado (una vez cada ~1 hora). Los comandos normales (bolos, basales, lecturas) van directamente entre tu teléfono y la bomba por Bluetooth, sin pasar por ningún servidor.

---

## 🔧 El pipeline de un comando (por debajo del capó)

Pongamos que quieres enviar un bolo de 2 unidades. Esto es lo que pasa internamente:

### Escribir a la bomba:

```
1. Construir el payload:  [200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]
   (200 = 2.0 × 100, tipo = 1 = bolo rápido)

2. Añadir CRC16:  payload + [0xAB, 0xCD]  (2 bytes de checksum)

3. Añadir contadores:  payload + reboot_counter(4B) + write_counter(8B)

4. Cifrar con XChaCha20-Poly1305:
   → Se genera un nonce aleatorio de 24 bytes
   → Se cifra con la clave compartida
   → El resultado = ciphertext + tag(16B) + nonce(24B)

5. Fragmentar: si el resultado > 19 bytes, se parte en trozos de 19
   con un header de 1 byte en cada trozo indicando "soy trozo X de Y"

6. Enviar por BLE: cada fragmento se escribe secuencialmente
```

### Leer de la bomba:

```
1. Leer la primera trama BLE (header dice cuántas tramas hay)
2. Si hay más tramas, leerlas de la característica "Extended Read"
3. Ensamblar todas las tramas (quitar headers, concatenar)
4. Descifrar con XChaCha20-Poly1305 (el nonce está al final)
5. Extraer los contadores (últimos 12 bytes) y sincronizarlos
6. Verificar CRC16
7. Parsear el payload
```

---

## ⚠️ Cosas importantes que debes saber

### La clave caduca (~1 hora)
La bomba invalida la clave periódicamente. El SDK lo detecta cuando falla un descifrado e intenta renovarla automáticamente. Si tienes el relay configurado, no necesitas hacer nada manual.

### Necesitas un relay server
Sin él, no puedes obtener claves de cifrado. El relay necesita un teléfono Android con root, la app mylife instalada, y Frida corriendo. No es trivial de montar, pero es factible. Estamos trabajando en documentar el setup paso a paso.

### Android 9+ (API 28)
El cifrado ChaCha20-Poly1305 requiere Android 9 como mínimo. La generación de claves X25519 requiere Android 13 (API 33) — para versiones anteriores se podría usar Bouncy Castle.

### Todo es Little Endian
Si estás implementando tu propia versión, recuerda: todo el protocolo usa Little Endian. Los bytes van "al revés" de lo que estás acostumbrado. Si algo no te funciona, lo primero que debes revisar es el byte order.

### TBR: usa porcentaje directo, NO centi-porcentaje
Si quieres una basal temporal del 50%, envías `50`, no `5000`. Este es un error que nos costó horas descubrir. Si envías centi-porcentaje la bomba devuelve error 130 (0x82).

---

## 🤝 ¿Cómo puedes contribuir?

Este proyecto es abierto y necesitamos ayuda en muchas áreas:

- **🧪 Testing**: Si tienes una YpsoPump y quieres probar, eres oro puro. Cada bomba es un entorno de test que necesitamos.
- **📱 Android/Kotlin**: El SDK está en Kotlin. Si dominas Android y BLE, puedes mejorar la estabilidad, añadir reconexión automática, optimizar el manejo de errores...
- **🔐 Criptografía**: Si entiendes de XChaCha20, X25519, o protocolos de intercambio de claves, revisa nuestra implementación. Seguro que hay cosas mejorables.
- **🐍 Python**: El proyecto original de referencia (Uneo7/Ypso) está en Python. Si prefieres Python, ahí hay trabajo.
- **📖 Documentación**: Si algo de este documento no queda claro, mejóralo. Los PRs de documentación son tan valiosos como los de código.
- **🔗 Integración con AndroidAPS**: El objetivo final para muchos es tener un driver de YpsoPump en AndroidAPS. Si conoces la arquitectura de AAPS, tu ayuda es crucial.
- **🖥️ Relay Server**: Documentar el setup del relay, hacer scripts de automatización, explorar alternativas al relay... todo esto es territorio por explorar.

---

## 📚 Documentación técnica completa

Si quieres profundizar en los detalles del protocolo, tenemos la **documentación técnica completa** con:

- Todos los UUIDs de servicios y características BLE
- Implementación completa del cifrado (con código Kotlin)
- Formato exacto de cada comando (byte por byte)
- Algoritmo CRC16 personalizado
- Formato GLB (variables seguras)
- Protocolo de framing (fragmentación de tramas)
- Flujo completo de intercambio de claves
- Gestión de contadores de cifrado
- Códigos de error conocidos

👉 [Documentación técnica en inglés (YpsoPump_Technical_Documentation_EN.md)](./YpsoPump_Technical_Documentation_EN.md)

---

## 🧭 Estado actual del proyecto

| Componente | Estado | Notas |
|-----------|:------:|-------|
| Documentación del protocolo | ✅ Completa | Este documento + doc técnica |
| SDK Android (Kotlin) | ✅ Funcional | Probado con bomba real |
| Cifrado XChaCha20-Poly1305 | ✅ Funcional | Sin dependencias externas |
| Intercambio de claves | ✅ Funcional | Requiere relay server |
| Relay Server | ⚠️ Prototipo | Funciona, pero necesita documentación |
| Renovación automática de claves | ✅ Funcional | Cada ~1 hora, transparente |

---

## 📝 Licencia y disclaimer

⚠️ **DISCLAIMER IMPORTANTE**: Este proyecto es fruto de ingeniería inversa con fines educativos y de interoperabilidad. No estamos afiliados a Ypsomed. Usar este código para controlar una bomba de insulina conlleva riesgos médicos reales. **Tú eres responsable de lo que hagas con esta información.** No somos médicos, no somos Ypsomed, y no podemos garantizar que esto sea seguro para uso clínico.

Dicho esto: miles de personas en la comunidad #WeAreNotWaiting llevan años controlando bombas Medtronic, Omnipod, Dana y otras con proyectos open source como AndroidAPS y Loop, y esta comunidad ha demostrado que la tecnología DIY puede funcionar (siempre con la debida precaución).

---

## 🙏 Agradecimientos

- **[Uneo7/Ypso](https://github.com/Uneo7/Ypso)**: El proyecto Python de referencia que abrió el camino.
- **La comunidad #WeAreNotWaiting**: Por demostrar que los pacientes tienen derecho a controlar sus propios dispositivos médicos.

---

**¿Tienes preguntas? ¿Quieres contribuir? Abre un issue o un PR. Esto lo construimos entre todos. 💪**
