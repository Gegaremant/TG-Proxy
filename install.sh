#!/bin/bash
#
# TG-Proxy — установка / запуск для Debian / Ubuntu (и других Linux)
#
# Скрипт сам скачивает последнюю версию TG-Proxy с GitHub Releases
# и устанавливает её:
#   * бинарник -> ~/.local/share/tg-proxy/            (или /opt/tg-proxy, если запущен от root)
#   * иконка   -> ~/.local/share/icons/hicolor/256x256/apps/
#   * ярлык    -> ~/.local/share/applications/tg-proxy.desktop (меню приложений)
#   * команда  -> tg-proxy (запуск из терминала)
#   * автозапуск при входе в систему (спросит)
#
# Автоматически определяет DISPLAY / XAUTHORITY (работает и на обычном
# рабочем столе, и в RDP/xrdp-сессиях). Права root: если нужно установить
# в /opt или поставить systemd-сервис — запустите с sudo, скрипт всё сделает.
#
# Использование:
#   ./install.sh                    # скачать последний релиз и установить
#   ./install.sh install            # то же самое
#   ./install.sh run                # скачать последний релиз и сразу запустить (без установки)
#   ./install.sh install ./TG-Proxy-1.2.1-Linux   # установить уже скачанный файл
#   ./install.sh run    ./TG-Proxy-1.2.1-Linux   # запустить уже скачанный файл
#   ./install.sh uninstall          # удалить установленную копию
#   ./install.sh --service          # дополнительно установить headless systemd-сервис
#
set -euo pipefail

APP_NAME="TG-Proxy"
BIN_NAME="tg-proxy"
REPO="Gegaremant/TG-Proxy"
DOWNLOAD_DIR="$HOME/.cache/tg-proxy"
VERSION=""

log()  { printf '\033[1;32m[install]\033[0m %s\n' "$*"; }
step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warning]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- Собираем аргументы ----------
MODE="install"
LOCAL_BIN=""
WITH_SERVICE=0

for arg in "$@"; do
  case "$arg" in
    install|run|uninstall) MODE="$arg" ;;
    --service) WITH_SERVICE=1 ;;
    *) LOCAL_BIN="$arg" ;;
  esac
done

# ---------- Пути установки (root -> системно, иначе -> пользовательски) ----------
if [[ "$EUID" -eq 0 ]]; then
  INSTALL_DIR="/opt/tg-proxy"
  DESKTOP_DIR="/usr/share/applications"
  ICON_DIR="/usr/share/icons/hicolor/256x256/apps"
  AUTOSTART_DIR="/etc/xdg/autostart"
  BIN_DIR="/usr/local/bin"
  SYSTEM_MODE=1
else
  INSTALL_DIR="$HOME/.local/share/tg-proxy"
  DESKTOP_DIR="$HOME/.local/share/applications"
  ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
  AUTOSTART_DIR="$HOME/.config/autostart"
  BIN_DIR="$HOME/.local/bin"
  SYSTEM_MODE=0
fi

# ---------- Скачивание ----------
download_bin() {
  step "1/3. Скачивание TG-Proxy"
  command -v curl >/dev/null 2>&1 || die \
    "Не найден curl. Установите: sudo apt install -y curl"

  if [[ -n "$LOCAL_BIN" ]]; then
    [[ -f "$LOCAL_BIN" ]] || die "Файл не найден: $LOCAL_BIN"
    FILE="$LOCAL_BIN"
    log "Использую локальный файл: $FILE"
    return 0
  fi

  mkdir -p "$DOWNLOAD_DIR"
  log "Получаю информацию о последнем релизе..."

  # Узнаём тег последнего релиза через redirect (HTML, без API-лимитов)
  TAG=$(curl -fsS -o /dev/null -w '%{redirect_url}' \
    "https://github.com/$REPO/releases/latest" 2>/dev/null \
    | sed -n 's#.*/releases/tag/\(v[^/]*\).*#\1#p')
  [[ -n "$TAG" ]] || die "Не удалось определить последний релиз."
  VERSION="${TAG#v}"
  log "Последняя версия: v$VERSION"

  # Имя Linux-бинарника из списка файлов релиза (HTML-эндпоинт expanded_assets)
  ASSET_NAME=$(curl -fsSL \
    "https://github.com/$REPO/releases/expanded_assets/$TAG" 2>/dev/null \
    | grep -oE 'TG-Proxy-[^"<]*Linux' | grep -v '\.dmg' | head -1)
  [[ -n "$ASSET_NAME" ]] || die "Не найден Linux-бинарник в последнем релизе."

  FILE="$DOWNLOAD_DIR/$ASSET_NAME"
  URL="https://github.com/$REPO/releases/latest/download/$ASSET_NAME"

  if [[ -f "$FILE" && -s "$FILE" ]]; then
    log "Последняя версия уже скачана (v$VERSION)"
  else
    log "Скачиваю $ASSET_NAME (v$VERSION)..."
    curl -fL --progress-bar -o "$FILE" "$URL" || die \
      "Ошибка скачивания: $URL"
  fi
  chmod +x "$FILE"
  log "Готово: $FILE"
  return 0
}

# ---------- Иконка ----------
download_icon() {
  mkdir -p "$ICON_DIR"
  local icon_file="$ICON_DIR/tg-proxy.png"
  if [[ ! -f "$icon_file" || ! -s "$icon_file" ]]; then
    curl -fsSL -o "$icon_file" \
      "https://raw.githubusercontent.com/$REPO/master/icon.png" \
      >/dev/null 2>&1 && log "Иконка: $icon_file" || \
      warn "Не удалось скачать иконку, ярлык будет без иконки."
  fi
}

# ---------- Проверка зависимостей ----------
check_deps() {
  step "2/3. Проверка зависимостей"
  local missing=()
  command -v curl >/dev/null 2>&1 || missing+=("curl")
  command -v xdg-open >/dev/null 2>&1 || true
  for dep in libnss3 libx11-6 libxcb1; do
    if command -v dpkg >/dev/null 2>&1; then
      dpkg -s "$dep" >/dev/null 2>&1 || true
    fi
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    warn "Рекомендуется установить: ${missing[*]}"
  fi
}

# ---------- Определяем дисплей ----------
detect_display() {
  if [[ -z "${DISPLAY:-}" ]] && [[ -z "${WAYLAND_DISPLAY:-}" ]]; then
    for d in :0 :1 :10 :11; do
      if command -v xdpyinfo >/dev/null 2>&1 \
         && DISPLAY="$d" xdpyinfo >/dev/null 2>&1; then
        export DISPLAY="$d"
        log "Дисплей выбран автоматически: $d"
        break
      fi
    done
  fi
  [[ -z "${XAUTHORITY:-}" && -f "$HOME/.Xauthority" ]] \
    && export XAUTHORITY="$HOME/.Xauthority"
  log "DISPLAY=${DISPLAY:-<пусто>}  WAYLAND=${WAYLAND_DISPLAY:-<пусто>}"
}

# ---------- Установка ----------
do_install() {
  download_bin
  check_deps
  detect_display

  step "3/3. Установка"
  mkdir -p "$INSTALL_DIR" "$DESKTOP_DIR" "$ICON_DIR" "$BIN_DIR" "$AUTOSTART_DIR"

  cp "$FILE" "$INSTALL_DIR/tg-proxy"
  chmod +x "$INSTALL_DIR/tg-proxy"
  log "Установлен: $INSTALL_DIR/tg-proxy"

  download_icon

  # Ярлык в меню
  cat >"$DESKTOP_DIR/tg-proxy.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=TG Proxy
Comment=MTProto WebSocket Proxy для Telegram (FakeTLS)
Exec="$INSTALL_DIR/tg-proxy" %U
Icon=$ICON_DIR/tg-proxy.png
Terminal=false
Categories=Network;Utility;
EOF
  chmod 644 "$DESKTOP_DIR/tg-proxy.desktop"
  command -v update-desktop-database >/dev/null 2>&1 \
    && update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
  log "Ярлык в меню: $DESKTOP_DIR/tg-proxy.desktop"

  # Автозапуск при входе в систему
  if [[ "$MODE" == "install" ]] && [[ -t 0 ]]; then
    printf '\033[1;36mВключить автозапуск TG Proxy при входе в систему? [Y/n]: \033[0m'
    read -r answer
    case "$answer" in
      n|N|no|No) : ;;
      *) cat >"$AUTOSTART_DIR/tg-proxy.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=TG Proxy
Exec="$INSTALL_DIR/tg-proxy"
Icon=$ICON_DIR/tg-proxy.png
Terminal=false
X-GNOME-Autostart-enabled=true
EOF
         chmod 644 "$AUTOSTART_DIR/tg-proxy.desktop"
         log "Автозапуск включён: $AUTOSTART_DIR/tg-proxy.desktop" ;;
    esac
  fi

  # Команда tg-proxy
  cat >"$BIN_DIR/$BIN_NAME" <<EOF
#!/bin/bash
exec "$INSTALL_DIR/tg-proxy" "\$@"
EOF
  chmod +x "$BIN_DIR/$BIN_NAME"
  log "Команда: $BIN_NAME"

  # Headless systemd-сервис (по запросу)
  if [[ "$WITH_SERVICE" -eq 1 ]]; then
    install_service
  fi

  step "Готово"
  if [[ "$SYSTEM_MODE" -eq 0 && ":$PATH:" != *":$BIN_DIR:"* ]]; then
    warn "$BIN_DIR нет в PATH."
    echo "    Добавьте в ~/.bashrc:  export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo "    Затем: source ~/.bashrc"
  fi
  echo ""
  echo "  Запуск из меню приложений:  TG Proxy"
  echo "  Запуск из терминала:        $BIN_NAME"
  echo "  Установлен:                 $INSTALL_DIR/tg-proxy"
  if [[ "$WITH_SERVICE" -eq 1 ]]; then
    echo "  Сервис:                     systemctl status tg-proxy"
  fi
}

# ---------- Headless systemd-сервис ----------
install_service() {
  if [[ "$SYSTEM_MODE" -ne 1 ]]; then
    die "--service требует прав root. Запустите: sudo ./install.sh --service"
  fi

  # Ищем headless-бинарник: рядом со скриптом, в dist/, в кэше релизов
  local svc=""
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  for cand in "$SCRIPT_DIR"/TG-Proxy-Service "$SCRIPT_DIR"/dist/TG-Proxy-Service \
              "$DOWNLOAD_DIR"/TG-Proxy-Service; do
    if [[ -f "$cand" && -x "$cand" ]]; then
      svc="$cand"; break
    fi
  done

  if [[ -z "$svc" ]]; then
    warn "TG-Proxy-Service не найден рядом. Сервис не установлен."
    echo "    Соберите его: pyinstaller packaging/linux-service.spec"
    return 0
  fi

  install -m 755 "$svc" "$INSTALL_DIR/TG-Proxy-Service"
  cat >/etc/systemd/system/tg-proxy.service <<EOF
[Unit]
Description=TG Proxy (headless WebSocket proxy for Telegram)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$INSTALL_DIR/TG-Proxy-Service
Restart=on-failure
RestartSec=5
Environment=TG_WS_PROXY_PORT=1443

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  log "Сервис установлен: /etc/systemd/system/tg-proxy.service"
  printf 'Включить и запустить сервис сейчас? [Y/n]: '
  read -r ans
  case "$ans" in
    n|N|no|No) : ;;
    *) systemctl enable --now tg-proxy ;;
  esac
}

# ---------- Запуск без установки ----------
do_run() {
  download_bin
  check_deps
  detect_display
  log "Запускаю: $FILE"
  exec "$FILE" "$@"
}

# ---------- Удаление ----------
do_uninstall() {
  rm -f "$DESKTOP_DIR/tg-proxy.desktop"
  rm -f "$ICON_DIR/tg-proxy.png"
  rm -f "$BIN_DIR/$BIN_NAME"
  rm -f "$AUTOSTART_DIR/tg-proxy.desktop"
  rm -rf "$INSTALL_DIR"
  if [[ "$SYSTEM_MODE" -eq 1 ]]; then
    systemctl stop tg-proxy >/dev/null 2>&1 || true
    systemctl disable tg-proxy >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/tg-proxy.service
    systemctl daemon-reload
  fi
  log "TG-Proxy удалён."
}

case "$MODE" in
  run)       do_run "$@" ;;
  uninstall) do_uninstall ;;
  *)         do_install ;;
esac