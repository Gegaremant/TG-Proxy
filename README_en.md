# TG-Proxy (by Gegaremant labs)

![TG-Proxy Logo](5.jpg)

[Русская версия / Russian Version](README.md)

**TG-Proxy** is a unified, cross-platform Telegram MTProto WebSocket Proxy with FakeTLS support designed to bypass censorship and DPI. It bundles versions for **Android**, **Windows**, **macOS**, and **Linux** under a single umbrella, offering a stylish, consistent user interface and identical features across all platforms.

[⬇️ DOWNLOAD LATEST RELEASE](https://github.com/Gegaremant/TG-Proxy/releases/latest)

> **Disclaimer:** This project was created for research and educational programming purposes. It is an author's vision and essentially a fork and essence of the best practices and codebases from the original repositories: [https://github.com/Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) and [https://github.com/ihtfw/tg-ws-proxy-android](https://github.com/ihtfw/tg-ws-proxy-android).

## Features
* **Fully Cross-Platform**: One app for Android (Jetpack Compose) and Desktop (CustomTkinter).
* **Auto Config**: Built-in latency scanner that iterates through dozens of pre-configured SNI domains (e.g., `sberbank.ru`, `vk.com`) and automatically selects the one with the lowest ping for FakeTLS traffic masking.
* **Presets**: Convenient "Home" and "Subway" profiles generating random high-range ports to avoid access restrictions and firewall rules (moving away from static 443/1080 ports).
* **Undetectable**: Masks traffic as ordinary HTTPS (TLS) using an `ee` secret format.

## Repository Structure
* `app/`, `gradle/` — Android app source code (Kotlin / Jetpack Compose).
* `proxy/`, `ui/`, `utils/`, `*.py` — Desktop app source code (Python / CustomTkinter for Windows, Linux, and macOS).
* `.gitignore` — Git ignore rules.
* `SNI_list.txt` — List of FakeTLS domains.
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
