# 🩸 YpsoPump DIY — How we managed to talk to the YpsoPump insulin pump

**An open project to control your YpsoPump from your own code.**

> 🧑‍🤝‍🧑 This document is written so anyone can understand it, whether you're a developer or not. If something's unclear, open an issue and we'll improve it together.

---

## What is this?

If you wear a **YpsoPump** you know that you can only control it from the official **mylife** app. Period. There's no public API, no documentation, no official way for a third-party app (like AndroidAPS, Loop, or any DIY project) to tell the pump "hey, deliver a 2-unit bolus".

Until now.

We've reverse-engineered the Bluetooth (BLE) protocol used by the YpsoPump and documented **everything**: how it connects, how it authenticates, how commands are encrypted, how to send boluses, temporary basals, how to read history... all of it.

The goal is to make this knowledge public so the community can build on top of it: AndroidAPS drivers, custom apps, monitoring tools, whatever you can think of.

---

## How does communication with the pump work? (Simple version)

Think of the pump as a safe with three locks. To give it any command, you need to open them in order:

### 🔓 Lock 1: Bluetooth Connection

The pump shows up as a regular Bluetooth device with a name like `YpsoPump_10175983`. Your phone sees it, connects, and you've got the virtual "cable" between the two.

### 🔓 Lock 2: Authentication (the password is the MAC address)

Here's the curious part: the pump's "password" isn't a PIN you choose. It's simply an MD5 hash of the pump's Bluetooth MAC address + a fixed salt. That is:

```
password = MD5(pump_MAC_address + secret_salt)
```

You get the MAC address automatically when you connect via Bluetooth, and the salt is always the same (we extracted it from the official app). So basically, if you can see the pump over Bluetooth, you can authenticate. No PIN, no passkey, nothing the user needs to type.

### 🔓 Lock 3: Encryption (here's where it gets serious)

Once authenticated, to send sensitive commands (boluses, basals, etc.) everything is encrypted with **XChaCha20-Poly1305** — military-grade encryption, literally. Every command you send is encrypted with a shared key between your app and the pump.

And here's **the big problem**: how do you get that key?

---

## 🔑 The key problem (and how we solved it)

### The wall: Play Integrity

Ypsomed (the company that makes the pump) uses a server called **Proregia** to manage encryption keys. When the official app needs a new key, it does this:

1. Asks the pump for a "challenge"
2. Asks Ypsomed's server to generate the key
3. But for the server to accept the request... it needs a **Google Play Integrity token**

And that token is the wall. Google Play Integrity is a system that tells the server: "I confirm that this request comes from the official **mylife** app, installed on a legitimate phone, no root, no modifications." If you build your own app, Google won't give you that token for the mylife package. Game over.

Or is it?

### The solution: a Relay Server (the "man in the middle")

What we've set up is a **relay server** — basically an intermediary that can actually get that token. How?

```
Your App  ──────►  Relay Server  ──────►  Ypsomed's Server (Proregia)
  │                     │                          │
  │  "I need            │  "Here's a valid         │
  │   a key             │   Play Integrity         │
  │   for my pump"      │   token + the            │
  │                     │   pump data"             │
  │                     │                          │
  │◄────────────────────│◄─────────────────────────│
  │   "Here's the       │   "Here's the            │
  │    encrypted        │    encrypted             │
  │    response"        │    response"             │
```

The relay server is an Android phone (rooted) with the official **mylife** app installed. Using **Frida** (an instrumentation tool), we intercept the Play Integrity function inside the official app and tell it: "hey, instead of requesting the token for your own nonce, request it for THIS nonce I'm giving you." Google generates the token (because the official app is legitimate), we capture it and pass it on to Ypsomed's server.

**In short**: the relay server is a bridge that uses the official app as a "master key" to obtain valid tokens.

### How often do you need to do this?

The key lasts **approximately 1 hour** (we've measured around 82 minutes). After that, the pump stops accepting your encrypted commands and you need a new key. The SDK we built automatically detects when the key has expired and requests a new one from the relay without you having to do anything.

### Is it single-use?

Yes. Each Play Integrity token is **single-use** (Google's anti-replay). Every time you need a new key, you need a new token. And each token is bound to ONE specific pump (by its Bluetooth address), so you can't reuse tokens across pumps.

---

## 📡 What can you do once you have the key?

With the connection established and the key in hand, you can:

| Action | Works? | Notes |
|--------|:------:|-------|
| Read pump status | ✅ | Current mode, remaining insulin, battery |
| Send a fast bolus | ✅ | Up to 25 units |
| Send an extended bolus | ✅ | With configurable duration |
| Cancel a bolus | ✅ | |
| Start a temporary basal rate (TBR) | ✅ | 0-200%, in 15-min steps |
| Cancel a temporary basal | ✅ | |
| Read/write basal profiles | ✅ | Programs A and B, 24 hourly rates |
| Switch active basal program | ✅ | A ↔ B |
| Synchronize date and time | ✅ | |
| Read event history | ✅ | Boluses, basals, alerts, cartridge changes... |
| Read bolus notifications | ✅ | Real-time via BLE notify |

Basically, **everything the official app does, we can do too**.

---

## 🏗️ Architecture: how the pieces fit together

```
┌─────────────────────────────────────────────────────┐
│                      YOUR APP                       │
│                                                     │
│  Sends high-level commands:                         │
│  "deliver a 1.5U bolus", "TBR at 50% for 30min"     │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│                   SDK (our code)                    │
│                                                     │
│  Handles all the dirty work:                        │
│  • Connect via BLE                                  │
│  • Authenticate with MD5(MAC + salt)                │
│  • Encrypt/decrypt with XChaCha20-Poly1305          │
│  • Fragment into BLE frames (max 19 bytes)          │
│  • Manage encryption counters                       │
│  • Automatically renew keys via relay               │
│  • Calculate CRC16 / encode GLB                     │
└──────────────────────┬──────────────────────────────┘
                       │ BLE
                       ▼
┌─────────────────────────────────────────────────────┐
│                    YPSOPUMP                         │
│                                                     │
│  Receives encrypted commands, executes them         │
│  and responds with encrypted data                   │
└─────────────────────────────────────────────────────┘

                    ╔═══════════════╗
                    ║ RELAY SERVER  ║  (only for obtaining keys,
                    ║ (phone with   ║   not for regular commands)
                    ║  Frida)       ║
                    ╚═══════════════╝
```

**Important**: the relay server is only needed to obtain encryption keys (once every ~1 hour). Regular commands (boluses, basals, reads) go directly between your phone and the pump over Bluetooth, without going through any server.

---

## 🔧 The pipeline of a command (under the hood)

Let's say you want to send a 2-unit bolus. Here's what happens internally:

### Writing to the pump:

```
1. Build the payload:  [200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]
   (200 = 2.0 × 100, type = 1 = fast bolus)

2. Append CRC16:  payload + [0xAB, 0xCD]  (2 bytes of checksum)

3. Append counters:  payload + reboot_counter(4B) + write_counter(8B)

4. Encrypt with XChaCha20-Poly1305:
   → A random 24-byte nonce is generated
   → Encrypted with the shared key
   → The result = ciphertext + tag(16B) + nonce(24B)

5. Fragment: if the result > 19 bytes, it's split into 19-byte chunks
   with a 1-byte header on each chunk indicating "I'm chunk X of Y"

6. Send via BLE: each fragment is written sequentially
```

### Reading from the pump:

```
1. Read the first BLE frame (header says how many frames there are)
2. If there are more frames, read them from the "Extended Read" characteristic
3. Assemble all frames (strip headers, concatenate)
4. Decrypt with XChaCha20-Poly1305 (the nonce is at the end)
5. Extract the counters (last 12 bytes) and synchronize them
6. Verify CRC16
7. Parse the payload
```

---

## ⚠️ Important things you should know

### The key expires (~1 hour)
The pump invalidates the key periodically. The SDK detects this when a decryption fails and tries to renew it automatically. If you have the relay configured, you don't need to do anything manually.

### You need a relay server
Without it, you can't obtain encryption keys. The relay needs a rooted Android phone, the mylife app installed, and Frida running. It's not trivial to set up, but it's doable. We're working on documenting the setup step by step.

### Android 9+ (API 28)
ChaCha20-Poly1305 encryption requires Android 9 at minimum. X25519 key generation requires Android 13 (API 33) — for earlier versions you could use Bouncy Castle.

### Everything is Little Endian
If you're implementing your own version, remember: the entire protocol uses Little Endian. The bytes go "backwards" from what you might be used to. If something isn't working, the first thing to check is byte order.

### TBR: use the direct percentage, NOT centi-percentage
If you want a 50% temporary basal, you send `50`, not `5000`. This is a mistake that cost us hours to figure out. If you send centi-percentage, the pump returns error 130 (0x82).

---

## 🤝 How can you contribute?

This project is open and we need help in many areas:

- **🧪 Testing**: If you have a YpsoPump and want to test, you're pure gold. Every pump is a test environment we need.
- **📱 Android/Kotlin**: The SDK is in Kotlin. If you're skilled in Android and BLE, you can improve stability, add automatic reconnection, optimize error handling...
- **🔐 Cryptography**: If you understand XChaCha20, X25519, or key exchange protocols, review our implementation. There's surely room for improvement.
- **🐍 Python**: The original reference project (Uneo7/Ypso) is in Python. If you prefer Python, there's work to be done there.
- **📖 Documentation**: If something in this document isn't clear, improve it. Documentation PRs are just as valuable as code PRs.
- **🔗 AndroidAPS Integration**: The ultimate goal for many is to have a YpsoPump driver in AndroidAPS. If you know the AAPS architecture, your help is crucial.
- **🖥️ Relay Server**: Documenting the relay setup, creating automation scripts, exploring relay alternatives... all of this is territory to explore.

---

## 📚 Complete Technical Documentation

If you want to dive into the protocol details, we have the **complete technical documentation** with:

- All BLE service and characteristic UUIDs
- Full encryption implementation (with Kotlin code)
- Exact format of each command (byte by byte)
- Custom CRC16 algorithm
- GLB format (secure variables)
- Framing protocol (frame fragmentation)
- Complete key exchange flow
- Encryption counter management
- Known error codes

👉 [Technical documentation in English (YpsoPump_Technical_Documentation_EN.md)](./YpsoPump_Technical_Documentation_EN.md)

---

## 🧭 Current Project Status

| Component | Status | Notes |
|-----------|:------:|-------|
| Protocol documentation | ✅ Complete | This document + technical docs |
| Android SDK (Kotlin) | ✅ Working | Tested with a real pump |
| XChaCha20-Poly1305 encryption | ✅ Working | No external dependencies |
| Key exchange | ✅ Working | Requires relay server |
| Relay Server | ⚠️ Prototype | Works, but needs documentation |
| Automatic key renewal | ✅ Working | Every ~1 hour, transparent |

---

## 📝 License and Disclaimer

⚠️ **IMPORTANT DISCLAIMER**: This project is the result of reverse engineering for educational and interoperability purposes. We are not affiliated with Ypsomed. Using this code to control an insulin pump carries real medical risks. **You are responsible for what you do with this information.** We are not doctors, we are not Ypsomed, and we cannot guarantee that this is safe for clinical use.

That said: thousands of people in the #WeAreNotWaiting community have been controlling Medtronic, Omnipod, Dana, and other pumps with open-source projects like AndroidAPS and Loop for years, and this community has proven that DIY technology can work (always with due caution).

---

## 🙏 Acknowledgments

- **[Uneo7/Ypso](https://github.com/Uneo7/Ypso)**: The reference Python project that paved the way.
- **The #WeAreNotWaiting community**: For proving that patients have the right to control their own medical devices.

---

**Got questions? Want to contribute? Open an issue or a PR. We build this together. 💪**
