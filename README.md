# 🎨 DrawTablet — Tavoletta Grafica Wireless

Trasforma il tuo tablet Android in una **tavoletta grafica wireless** per il tuo PC Windows.
Comunicazione via **Socket TCP/IP** sulla rete locale Wi-Fi.

---

## 📁 Struttura del progetto

```
DrawTablet/
├── android/          ← Progetto Android (Kotlin)
│   └── app/src/main/
│       ├── java/com/drawtablet/
│       │   ├── MainActivity.kt       ← Activity principale
│       │   ├── DrawingView.kt        ← Canvas di disegno
│       │   └── ConnectionManager.kt  ← Gestione TCP
│       ├── res/layout/activity_main.xml
│       ├── res/drawable/             ← Icone e sfondi
│       └── AndroidManifest.xml
│
└── windows/          ← Progetto Windows WPF (C#)
    ├── MainWindow.xaml               ← UI
    ├── MainWindow.xaml.cs            ← Logica server TCP
    ├── App.xaml / App.xaml.cs
    └── DrawTabletPC.csproj
```

---

## 🖥️ PARTE 1 — App Windows (Server)

### Prerequisiti
- **Windows 10/11**
- **.NET 8 SDK** → https://dotnet.microsoft.com/download/dotnet/8.0

### Compilazione e avvio

```bash
# Entra nella cartella windows
cd DrawTablet/windows

# Compila e avvia
dotnet run

# Oppure crea l'eseguibile standalone
dotnet publish -c Release -r win-x64 --self-contained true
# L'exe sarà in: bin/Release/net8.0-windows/win-x64/publish/DrawTabletPC.exe
```

### Cosa vedi all'avvio
- L'app mostra automaticamente il tuo **IP locale** (es. `192.168.1.50`)
- Rimane in ascolto sulla **porta 9999**
- ⚠️ Se il firewall di Windows lo blocca, clicca su "Consenti accesso"

---

## 📱 PARTE 2 — App Android (Client)

### Prerequisiti
- **Android Studio** (Hedgehog o più recente) → https://developer.android.com/studio
- **SDK Android 21+** (installato tramite Android Studio)
- Tablet o smartphone Android con **Android 5.0+**

### Apertura del progetto
1. Apri **Android Studio**
2. Clicca **File → Open** e seleziona la cartella `DrawTablet/android`
3. Attendi la sincronizzazione Gradle

### Compilazione APK

**Metodo A — Debug (più veloce, per test):**
1. Collega il tablet via USB oppure usa un emulatore
2. Clicca ▶ **Run** (o Shift+F10)
3. L'APK viene installato direttamente

**Metodo B — APK firmato (per distribuzione):**
1. Menu **Build → Generate Signed Bundle/APK**
2. Scegli **APK**
3. Crea un nuovo keystore o usa uno esistente
4. Scegli variante **release**
5. Il file `.apk` sarà in `app/release/app-release.apk`

**Metodo C — APK debug via terminale:**
```bash
cd DrawTablet/android
./gradlew assembleDebug
# APK in: app/build/outputs/apk/debug/app-debug.apk
```

### Installazione APK su tablet (senza cavo)
1. Copia `app-debug.apk` sul tablet (via email, Google Drive, USB)
2. Sul tablet: **Impostazioni → Sicurezza → Origini sconosciute** → Abilita
3. Apri il file APK con il file manager e installa

---

## 🔌 Come usare

### Prerequisiti di rete
- Tablet e PC devono essere sulla **stessa rete Wi-Fi**

### Avvio

1. **Sul PC**: avvia `DrawTabletPC.exe`
   - Nota l'IP mostrato in basso, es. `192.168.1.50`

2. **Sul Tablet**: apri l'app DrawTablet
   - Inserisci l'IP del PC nel campo in alto
   - Tocca **"Connetti"**
   - Lo stato diventa ✅ Verde su entrambi

3. **Inizia a disegnare!** I tratti appaiono in tempo reale sul PC.

---

## 🎨 Strumenti disponibili

| Icona | Strumento | Funzione |
|-------|-----------|----------|
| ✏️ | **Matita** | Tratto preciso, bordi netti |
| 🖌️ | **Pennello** | Tratto morbido con effetto blur |
| 🧽 | **Gomma** | Cancella in bianco |
| 🪣 | **Riempimento** | Flood fill area (su Android) |
| 🎨 | **Colore** | Scegli tra 12 colori predefiniti |
| 〰️ | **Spessore** | Slider 2px → 50px |
| 🗑️ | **Pulisci tutto** | Cancella tutto il canvas (sync al PC) |
| 💾 | **Salva** | Salva PNG in Galleria (Android) o file scelto (PC) |

---

## ⚙️ Protocollo TCP

Il tablet invia comandi di testo sulla porta 9999:

```
DOWN|nx|ny|color|stroke|tool   ← inizio tratto
MOVE|nx|ny                     ← punto intermedio
UP|nx|ny                       ← fine tratto
CLEAR                          ← pulisci canvas
FILL|nx|ny|color               ← riempimento
```

- `nx`, `ny` = coordinate normalizzate (0.0 → 1.0) per adattarsi a qualsiasi dimensione schermo
- `color` = intero ARGB
- `tool` = PENCIL | BRUSH | ERASER | FILL

---

## 🔧 Troubleshooting

| Problema | Soluzione |
|----------|-----------|
| Non si connette | Verifica che siano sulla stessa Wi-Fi. Disabilita temporaneamente il firewall Windows |
| APK non si installa | Abilita "Origini sconosciute" nelle impostazioni sicurezza |
| Tratto sfasato | Usa la modalità landscape sul tablet (già impostata di default) |
| Gradle sync fallisce | In Android Studio: File → Invalidate Caches → Restart |
| `dotnet` non trovato | Installa .NET 8 SDK da https://dotnet.microsoft.com |

---

## 📋 Requisiti sistema

| | Android | Windows |
|---|---|---|
| OS | Android 5.0+ (API 21) | Windows 10/11 |
| Runtime | — | .NET 8 |
| Rete | Wi-Fi | Wi-Fi / Ethernet |
| Permessi | Internet, Storage | Firewall porta 9999 |
