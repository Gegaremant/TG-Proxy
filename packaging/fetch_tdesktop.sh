#!/bin/bash
#
# fetch_tdesktop.sh — скачивает последние версии Telegram Desktop (tdesktop)
# для Linux/macOS/Windows (x64) с GitHub Releases в указанную папку.
# Используется локально и в GitHub Actions (для загрузки в наш релиз).
#
# Использование:
#   ./packaging/fetch_tdesktop.sh [OUT_DIR]   # по умолчанию: releases/tgdesktop
#
set -euo pipefail

TD_REPO="telegramdesktop/tdesktop"
OUT_DIR="${1:-releases/tgdesktop}"

log()  { printf '\033[1;32m[tdesktop]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "Не найден curl."

mkdir -p "$OUT_DIR"
log "Получаю информацию о последнем релизе $TD_REPO ..."

# Тег последнего релиза через redirect (HTML, без API-лимитов)
TAG=$(curl -fsS -o /dev/null -w '%{redirect_url}' \
  "https://github.com/$TD_REPO/releases/latest" 2>/dev/null \
  | sed -n 's#.*/releases/tag/\(v[^/]*\).*#\1#p')
[[ -n "$TAG" ]] || die "Не удалось определить последний релиз tdesktop."
TD_VER="${TAG#v}"
log "Последняя версия: v$TD_VER"

ASSETS=$(curl -fsSL \
  "https://github.com/$TD_REPO/releases/expanded_assets/$TAG" 2>/dev/null)

# Имя файла по паттерну (с фолбэком на старые имена tsetup.*)
pick() { # pick <grep_pattern> [fallback_pattern]
  local name=""
  name=$(printf '%s' "$ASSETS" | grep -oE "$1" | head -1)
  [[ -z "$name" && -n "${2:-}" ]] && \
    name=$(printf '%s' "$ASSETS" | grep -oE "$2" | head -1)
  printf '%s' "$name"
}

download() { # download <asset_name> <label>
  local name="$1" label="$2"
  [[ -n "$name" ]] || die "Не найден файл для $label в релизе $TAG."
  local dest="$OUT_DIR/$name"
  if [[ -f "$dest" && -s "$dest" ]]; then
    log "Уже скачан: $dest"
    return 0
  fi
  log "Скачиваю $label: $name ..."
  curl -fL --retry 3 --progress-bar -o "$dest" \
    "https://github.com/$TD_REPO/releases/download/$TAG/$name" \
    || { rm -f "$dest"; die "Ошибка скачивания: $name"; }
  log "Готово ($(du -h "$dest" | cut -f1)): $dest"
}

# --- Linux x64 ---
LINUX=$(pick 'td-setup-linux-x64-[0-9.]+\.tar\.xz' 'tsetup\.[0-9.]+\.tar\.xz')
# --- Windows x64 ---
WINDOWS=$(pick 'td-setup-win-x64-[0-9.]+\.exe' 'tsetup\.[0-9.]+\.exe')
# --- macOS ---
MAC=$(pick 'td-setup-mac-[0-9.]+\.dmg' 'tsetup\.[0-9.]+\.dmg')

download "$LINUX" "Linux"
download "$WINDOWS" "Windows x64"
download "$MAC" "macOS"

log "Готово. Файлы в: $OUT_DIR"