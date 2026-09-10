#!/bin/bash
# TG Proxy - Linux Installer
# Установщик для Ubuntu/Debian-based дистрибутивов

set -e

INSTALL_DIR="/opt/tg-proxy"
SERVICE_NAME="tg-proxy"
DESKTOP_FILE="/usr/share/applications/tg-proxy.desktop"

echo "=== TG Proxy - Установщик ==="
echo ""

# Проверка root
if [ "$EUID" -ne 0 ]; then
    echo "Запустите скрипт от имени root:"
    echo "sudo ./install.sh"
    exit 1
fi

# Проверка наличия исполняемых файлов
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# GUI-бинарник: в корне или в dist/
GUI_BINARY_PATH=""
SERVICE_BINARY_PATH=""
for cand in "$SCRIPT_DIR"/TG-Proxy*Linux "$SCRIPT_DIR"/dist/TG-Proxy*Linux; do
    if [ -f "$cand" ]; then
        GUI_BINARY_PATH="$cand"
        break
    fi
done
for cand in "$SCRIPT_DIR"/dist/TG-Proxy-Service; do
    if [ -f "$cand" ]; then
        SERVICE_BINARY_PATH="$cand"
        break
    fi
done

if [ -z "$GUI_BINARY_PATH" ]; then
    echo "ОШИБКА: Не найден GUI-бинарник TG-Proxy*Linux"
    echo "Сначала соберите:"
    echo "  pip install pyinstaller"
    echo "  pyinstaller packaging/linux.spec"
    exit 1
fi

echo "Найден GUI-бинарник: $(basename "$GUI_BINARY_PATH")"
if [ -n "$SERVICE_BINARY_PATH" ]; then
    echo "Найден Service-бинарник: $(basename "$SERVICE_BINARY_PATH")"
else
    echo "Service-бинарник не найден — соберите его отдельно: pyinstaller packaging/linux-service.spec"
fi
echo "Установка в: $INSTALL_DIR"
echo ""

# Создание директории
mkdir -p "$INSTALL_DIR"

# Копирование GUI-бинарника
install -m 755 "$GUI_BINARY_PATH" "$INSTALL_DIR/TG-Proxy"

# Копирование Service-бинарника (headless), если есть
if [ -n "$SERVICE_BINARY_PATH" ]; then
    install -m 755 "$SERVICE_BINARY_PATH" "$INSTALL_DIR/TG-Proxy-Service"
fi

# Копирование иконки (если есть) в pixmaps и hicolor-тему
if [ -f "$SCRIPT_DIR/icon.png" ]; then
    cp "$SCRIPT_DIR/icon.png" "$INSTALL_DIR/icon.png"

    # hicolor (стандартный путь для значков приложений)
    HICOLOR="/usr/share/icons/hicolor/256x256/apps"
    mkdir -p "$HICOLOR"
    cp "$SCRIPT_DIR/icon.png" "$HICOLOR/tg-proxy.png"

    # pixmaps (запасной вариант)
    if [ -d "/usr/share/pixmaps" ]; then
        cp "$SCRIPT_DIR/icon.png" /usr/share/pixmaps/tg-proxy.png
    fi
fi

# Копирование .desktop файла и обновление кэша меню
cp "$SCRIPT_DIR/packaging/tg-proxy.desktop" "$DESKTOP_FILE"
chmod 644 "$DESKTOP_FILE"
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
fi

# Создание systemd сервиса
cp "$SCRIPT_DIR/packaging/tg-proxy.service" /etc/systemd/system/
systemctl daemon-reload

echo "Установка завершена!"
echo ""

# GUI автозапуск (XDG autostart) — запуск трея при входе в систему
AUTOSTART_FILE="$HOME/.config/autostart/tg-proxy.desktop"
if [ -n "$SUDO_USER" ]; then
    AUTOSTART_FILE="/home/$SUDO_USER/.config/autostart/tg-proxy.desktop"
fi
echo "=== Автозапуск GUI при входе в систему ==="
read -r -p "Запускать TG Proxy (трей) при входе в систему? [y/N]: " GUI_ANS
case "${GUI_ANS,,}" in
    y|yes)
        mkdir -p "$(dirname "$AUTOSTART_FILE")"
        install -m 644 "$SCRIPT_DIR/packaging/tg-proxy-autostart.desktop" "$AUTOSTART_FILE"
        echo "ОК: GUI будет запускаться автоматически ($AUTOSTART_FILE)"
        ;;
    *)
        echo "GUI-автозапуск не включён."
        ;;
esac
echo ""

# Настройка systemd-сервиса (автозагрузка без GUI)
if command -v systemctl >/dev/null 2>&1 && [ -n "$SERVICE_BINARY_PATH" ]; then
    echo "=== Настройка сервиса автозапуска (headless) ==="
    echo "ВНИМАНИЕ: сервис использует отдельный CLI-бинарник (без трея)."
    echo "Убедитесь, что secret в /etc/systemd/system/tg-proxy.service заполнен,"
    echo "иначе удалите строку Environment=TG_WS_PROXY_SECRET="
    read -r -p "Включить автозапуск прокси как systemd-сервис? [y/N]: " ANS
    case "${ANS,,}" in
        y|yes)
            systemctl enable "$SERVICE_NAME" 2>/dev/null || echo " (!) Не удалось включить автозагрузку"
            systemctl start "$SERVICE_NAME" 2>/dev/null || echo " (!) Не удалось запустить сервис"
            echo "Сервис '$SERVICE_NAME' включён и запущен."
            echo "Управление: systemctl status|stop|restart $SERVICE_NAME"
            echo "Логи: journalctl -u $SERVICE_NAME -f"
            ;;
        *)
            echo "Автозапуск не включён. Включить позже:"
            echo "  sudo systemctl enable --now $SERVICE_NAME"
            ;;
    esac
fi

echo ""
echo "=== Запуск ==="
echo ""
echo "GUI режим (с трей-иконкой):"
echo "  $INSTALL_DIR/TG-Proxy"
echo ""
echo "Или через меню приложений:"
echo "  Найдите 'TG Proxy'"
echo ""
