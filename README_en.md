# TG-Proxy (by Gegaremant labs)

![TG-Proxy Logo](5.jpg)

[Русская версия / Russian Version](README.md)

**TG-Proxy** is a unified, cross-platform Telegram MTProto WebSocket Proxy with FakeTLS support designed to bypass censorship and DPI. It bundles versions for **Android**, **Windows**, **macOS**, and **Linux** under a single umbrella, offering a stylish, consistent user interface and identical features across all platforms.

[⬇️ DOWNLOAD LATEST RELEASE](https://github.com/Gegaremant/TG-Proxy/releases/latest)

### 🤖 Android: if the APK won't install

<details>
<summary><code>INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match</code></summary>

An older build signed with a different key is still present on the device.
A plain uninstall does not always remove every profile/clone:

1. **Settings → Apps → TG-Proxy → Uninstall** (prefer "Uninstall for all
   users" if offered).
2. Check app clones: **"Dual apps" / "Second space" / "App cloner" /
   "Secure Folder"** — a hidden copy may live there; remove it too.
3. Still failing — use adb:
   ```bash
   adb shell pm list users          # list device profiles
   adb shell pm uninstall --user 0 org.flowseal.tgwsproxy
   # repeat for other profiles: pm uninstall --user <id> org.flowseal.tgwsproxy
   ```
4. Then install the latest APK from the download section.

</details>

<details>
<summary>Proxy stalls a minute after the app loses focus</summary>

Built-in (OEM) battery optimizations freeze the app and drop its
connections. The in-app readiness checklist (visible even while the proxy
is running) walks you through:

* "Unrestricted data usage" (Mobile data & Wi-Fi → allow unrestricted),
* unrestricted background battery usage,
* in battery/app settings: **disable** "Pause activity when unused" and
  "Stop when idle", enable autostart.

Wording differs across shells (HyperOS, ColorOS, One UI, etc.) — each
checklist row opens the right screen.
</details>

> **Disclaimer:** This project was created for research and educational programming purposes. It is an author's vision and essentially a fork and essence of the best practices and codebases from the original repositories: [https://github.com/Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) and [https://github.com/ihtfw/tg-ws-proxy-android](https://github.com/ihtfw/tg-ws-proxy-android).

## Features
* **Fully Cross-Platform**: One app for Android (Jetpack Compose) and Desktop (CustomTkinter).
* **Auto Config**: Built-in latency scanner that iterates through dozens of pre-configured SNI domains (e.g., `sberbank.ru`, `vk.com`) and automatically selects the one with the lowest ping for FakeTLS traffic masking.
* **Undetectable**: Masks traffic as ordinary HTTPS (TLS) using an `ee` secret format.
* **Dark/Light Theme**: Toggle it right in the app header.
* **Upstream SOCKS5**: Optional external SOCKS5 proxy (e.g., Hiddify) for all outbound traffic.

## Repository Structure
* `app/`, `gradle/` — Android app source code (Kotlin / Jetpack Compose).
* `proxy/`, `ui/`, `utils/`, `*.py` — Desktop app source code (Python / CustomTkinter for Windows, Linux, and macOS).
* `.gitignore` — Git ignore rules.
* `icon.png` / `5.jpg` — Project graphic assets.

## Building from source
For your convenience, GitHub Actions (`.github/workflows`) are configured in this repository. They automatically build the Android APK and Desktop executables on every push to the `main` branch.

### Local Build (Android)
1. Open the project root in Android Studio or use the command line.
2. Run: `./gradlew assembleDebug` (or `assembleRelease`).

### Local Build (Desktop)
The desktop versions are built using `python`, `hatch`, and `pyinstaller`.
1. Open a terminal in the root folder.
2. Install dependencies: `pip install -e .`
3. Build the executable: `pyinstaller packaging/windows.spec` (or `macos.spec`, `linux.spec`).

## Other projects by Gegaremant labs
* 🤖 **KatYa virtual assistant**: [https://github.com/Gegaremant/KatYa_2](https://github.com/Gegaremant/KatYa_2)
* 🧠 **Free Brain**: [https://github.com/Gegaremant/API_Brain_for_LLM](https://github.com/Gegaremant/API_Brain_for_LLM)
* 🔩 ⚙️🗜️ **build LLM SRV in garbidge**: [https://github.com/Gegaremant/LLM_Server_Ecosystem](https://github.com/Gegaremant/LLM_Server_Ecosystem)
