# TG-Proxy (от Gegaremant labs)

![TG-Proxy Logo](TG2.jpg)

[English Version / Английская версия](README_en.md)

### ⬇️ Скачать для своей ОС
[![Windows](https://img.shields.io/badge/Windows-1.2.1-0078D6?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/Gegaremant/TG-Proxy/releases/latest/download/TG-Proxy-1.2.1-Windows.exe) [![macOS](https://img.shields.io/badge/macOS-1.2.1-333333?style=for-the-badge&logo=apple&logoColor=white)](https://github.com/Gegaremant/TG-Proxy/releases/latest/download/TG-Proxy-1.2.1-MAC.dmg)

[![Linux](https://img.shields.io/badge/Linux-1.2.1-FCC624?style=for-the-badge&logo=linux&logoColor=black)](https://github.com/Gegaremant/TG-Proxy/releases/latest/download/TG-Proxy-1.2.1-Linux) [![Android](https://img.shields.io/badge/Android-1.2.1-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Gegaremant/TG-Proxy/releases/latest/download/TG-Proxy-1.2.1-Android.apk)

**TG-Proxy** — это единое кроссплатформенное приложение для обхода блокировок Telegram на основе MTProto WebSocket Proxy с поддержкой FakeTLS. Оно объединяет в себе версии для **Android**, **Windows**, **macOS** и **Linux** под одним капотом, предлагая единый стильный интерфейс и одинаковый набор функций на всех платформах.

[⬇️ СКАЧАТЬ LATEST RELEASE (ПОСЛЕДНЮЮ ВЕРСИЮ)](https://github.com/Gegaremant/TG-Proxy/releases/latest)

> **Дисклеймер:** Данный проект создан в исследовательских целях в качестве тренировки по программированию. Он является авторским видением и, по сути, эссенцией и форком лучших наработок оригинальных репозиториев: [https://github.com/Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) и [https://github.com/ihtfw/tg-ws-proxy-android](https://github.com/ihtfw/tg-ws-proxy-android).

## Возможности
* **Полная кроссплатформенность**: Одно приложение для Android (Jetpack Compose) и ПК (CustomTkinter).
* **Auto Config (Автонастройка)**: Встроенный сканер, который автоматически проверяет десятки зашитых SNI-доменов (например, `sberbank.ru`, `vk.com`) и выбирает домен с наименьшим пингом для маскировки трафика (FakeTLS).
* **Защита от обнаружения**: Маскировка трафика под обычный HTTPS (TLS) с использованием `ee` secret.
* **Тёмная/светлая тема**: переключается прямо в шапке приложения.
* **Upstream SOCKS5**: поддержка внешнего SOCKS5-прокси (например, Hiddify) для всего исходящего трафика.

## Структура репозитория
* `app/`, `gradle/` — Исходный код Android-приложения (Kotlin/Jetpack Compose).
* `proxy/`, `ui/`, `utils/`, `*.py` — Исходный код ПК-версии (Python/CustomTkinter для Windows, Linux, macOS).
* `icon.png` / `TG2.jpg` — Графические ресурсы проекта.

## Сборка из исходников
Для вашего удобства в проекте настроены GitHub Actions (`.github/workflows`), которые автоматически собирают APK-файл для Android и исполняемые файлы для настольных операционных систем при каждом push'е в ветку `main`.

### Локальная сборка (Android)
1. Откройте корень проекта в Android Studio или используйте командную строку.
2. Выполните: `./gradlew assembleDebug` (или `assembleRelease`).

### Локальная сборка (ПК)
Для сборки используются `python`, `hatch` и `pyinstaller`.
1. Откройте терминал в корневой папке проекта.
2. Установите зависимости: `pip install -e .`
3. Соберите исполняемый файл: `pyinstaller packaging/windows.spec` (или `macos.spec`, `linux.spec`).

### Установка и запуск на Linux
Подробная инструкция: см. [LINUX_INSTALL.md](LINUX_INSTALL.md).
Быстро: скачайте `install.sh` со страницы релизов и запустите `./install.sh` —
установщик сам скачает последнюю версию, поставит иконку и пункт меню,
предложит автозапуск, а с `sudo ./install.sh --service` добавит headless-сервис
через systemd.

### Установка и запуск на macOS
Подробная инструкция: см. [MACOS_INSTALL.md](MACOS_INSTALL.md).
Кратко: скачайте `.dmg` со страницы релизов, откройте его и перетащите
приложение в папку «Программы». При первом запуске разрешите приложение в
«Системные настройки → Конфиденциальность и безопасность», если macOS
заблокирует его как неподписанное.

При запуске прокси автоматически определяет, в каком режиме он уже работает
(GUI-трей или headless-сервис), сообщает об этом и предлагает переключиться между
режимами (см. `utils/runtime_mode.py`).

## Другие проекты Gegaremant labs
* 🤖 **виртуальный ассистент KatYa**: [https://github.com/Gegaremant/KatYa_2](https://github.com/Gegaremant/KatYa_2)
* 🧠 **Free Brain**: [https://github.com/Gegaremant/API_Brain_for_LLM](https://github.com/Gegaremant/API_Brain_for_LLM)
* 🔩 ⚙️🗜️ **build LLM SRV in garbidge**: [https://github.com/Gegaremant/LLM_Server_Ecosystem](https://github.com/Gegaremant/LLM_Server_Ecosystem)
