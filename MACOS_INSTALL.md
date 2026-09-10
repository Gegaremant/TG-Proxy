# Установка и запуск TG Proxy на macOS

TG-Proxy собирается в `.dmg`-образ для macOS (Intel и Apple Silicon через
`osxcross`/нативный `pyinstaller`). Инструкция ниже охватывает оба случая.

## 1. Установка из готового релиза

1. Скачайте **TG-Proxy-1.2.1-MAC.dmg** со страницы релизов:
   https://github.com/Gegaremant/TG-Proxy/releases/latest
2. Откройте скачанный `.dmg` (двойной клик).
3. Перетащите иконку **TG FREE ...app** в папку «Программы».
4. Извлеките образ (ПКМ по иконке на рабочем столе → «Извлечь»).

## 2. Первый запуск

Приложение не подписано и не нотаризовано (разработчик не в Apple Developer
Program), поэтому macOS будет блокировать его при первом запуске. Чтобы
разрешить:

* **Способ А (Gatekeeper):** ПКМ по `TG FREE ...app` в папке «Программы» →
  «Открыть» → снова «Открыть» в диалоге.
* **Способ Б (если не помогло):**
  ```bash
  sudo spctl --master-disable          # отключить Gatekeeper полностью (не рекомендуется)
  # или разово для файла:
  xattr -d com.apple.quarantine "/Applications/TG FREE ...app"
  ```
* **Способ В:** «Системные настройки → Конфиденциальность и безопасность» →
  внизу появится «Программа заблокирована» → «Открыть всё равно».

## 3. Что делает приложение

После запуска иконка появляется в системном трее (меню-баре):

* ЛКМ — открыть прокси в Telegram;
* ПКМ — копировать ссылку, перезапустить, настройки, логи, выход.

На первый запуск приложение проверяет доступность API Telegram и только после
успешной проверки показывает ссылку для подключения
(`tg://proxy?server=127.0.0.1&port=…&secret=dd…`).

## 4. Сборка из исходников

```bash
# на машине с macOS
pip install -e .
pyinstaller packaging/macos.spec
packaging/dmg/build_dmg.sh "dist/TG FREE ...app" "TG-Proxy" "dist/TG-Proxy-1.2.1-MAC.dmg"
```

Иконка приложения (.icns) генерируется на лету:

```bash
python macos.py --render-app-icon icon.icns
```

## 5. Автозапуск

На macOS автозапуск настраивается в системных настройках:
«Пользователи и группы → Элементы входа» (добавьте приложение) или через
`osascript`:

```bash
osascript -e 'tell application "System Events" to make login item at end with properties {path:"/Applications/TG FREE ...app", hidden:false}'
```

## 6. Удаление

```bash
rm -rf "/Applications/TG FREE ...app"
rm -rf ~/Library/Logs/tg_proxy ~/Library/Application\ Support/TG-Proxy
```