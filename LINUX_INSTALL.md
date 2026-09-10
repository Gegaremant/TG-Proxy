# Установка и запуск TG Proxy на Linux

`install.sh` — универсальный установщик: **сам скачивает последний релиз** с GitHub,
определяет типы окружения (root/пользователь, X11/Wayland) и ставит всё необходимое.

## 1. Установка (быстро)

Скачайте со страницы релизов **`install.sh`** (или вместе с ним `TG-Proxy-1.x.x-Linux`)
и выполните:

```bash
chmod +x install.sh
./install.sh            # сам скачает последний релиз и установит (можно без sudo)
# или
sudo ./install.sh       # установит системно в /opt/tg-proxy
```

Скрипт:
- определяет последнюю версию и скачивает Linux-бинарник (**если файл уже рядом — использует его**);
- устанавливает бинарник, иконку и пункт меню **TG Proxy**;
- создаёт команду запуска из терминала: `tg-proxy`;
- **спрашивает**, включить ли автозапуск при входе в систему;
- с `--service` дополнительно ставит headless-прокси как systemd-сервис.

Другие режимы:

```bash
./install.sh run                                  # скачать и сразу запустить (без установки)
./install.sh install ./TG-Proxy-1.2.1-Linux       # установить уже скачанный файл
./install.sh uninstall                            # удалить установленную копию
sudo ./install.sh --service                       # + headless systemd-сервис (нужен root)
```

## 2. Ручная сборка (необязательно)

Проект собирает **два** бинарника для Linux:

| Бинарник | Назначение |
|----------|-----------|
| `TG-Proxy-1.2.1-Linux` | GUI-версия с трей-иконкой (для рабочего стола) |
| `TG-Proxy-Service` | Headless CLI-версия (для systemd-сервиса, без GUI) |

Подготовка (нужен Python 3.11+ и PyInstaller):

```bash
pip install pyinstaller psutil==7.0.0 "Pillow==12.1.1" \
    "cryptography==46.0.5" customtkinter==5.2.2 \
    pystray==0.19.5 pyperclip==1.9.0 certifi hatchling
```

GUI-версия:

```bash
pyinstaller packaging/linux.spec
```

Headless-версия (для сервиса):

```bash
pyinstaller packaging/linux-service.spec
```

Результат появится в папке `dist/`.

## 3. Запуск GUI (трей-иконка)

```bash
/opt/tg-proxy/TG-Proxy
```

Или через меню приложений → **TG Proxy**. После запуска иконка появляется в системном трее:
- ЛКМ — открыть прокси в Telegram;
- ПКМ — копировать ссылку, перезапустить, настройки, логи, выход.

Виджет также можно запустить напрямую из исходников: `python linux.py`.

### 3.1 Проверка запущенного режима (GUI / сервис)

При запуске приложение автоматически определяет, не работает ли прокси уже — и в каком
режиме (GUI-трей или headless-сервис):

* **Уже запущен сервис (headless)** — появится сообщение «Прокси уже запущен как
  headless-сервис (PID …)» и предложение **остановить сервис и запустить GUI-версию**.
  При согласии сервис (`systemctl stop tg-proxy`) останавливается, и GUI занимает порт.
* **Уже запущен GUI** — при повторном запуске появится сообщение, что прокси работает
  в GUI-режиме (с указанием PID), и запуск не дублируется (single-instance).
* **Запуск службы при работающем GUI** — headless-бинарник выводит предупреждение в лог
  о конфликте порта, если GUI уже запущен.

Определение режима основано на сканировании процессов: GUI (`TG-Proxy*Linux`/
`linux.py`) и сервис (`TG-Proxy-Service`/`tg_ws_proxy.py`). Код находится в
`utils/runtime_mode.py`.

## 4. Автозапуск GUI при входе в систему

Установщик **сам спросит** про автозапуск (файл
`~/.config/autostart/tg-proxy.desktop` или `/etc/xdg/autostart/` при root).
Вручную:

```bash
mkdir -p ~/.config/autostart
cp packaging/tg-proxy-autostart.desktop ~/.config/autostart/
```

## 5. Headless-сервис (systemd)

Установка сервиса одной командой (нужен root, бинарник `TG-Proxy-Service`
ищется рядом со скриптом или в `dist/`):

```bash
sudo ./install.sh --service
```

При ручной установке создайте юнит и заполните секретный ключ
(`32` hex-символа), либо удалите строку `Environment=TG_WS_PROXY_SECRET=`:

```bash
sudo nano /etc/systemd/system/tg-proxy.service
```

Управление:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tg-proxy   # включить автозагрузку и запустить
sudo systemctl status tg-proxy         # статус
sudo systemctl stop tg-proxy           # остановить
sudo systemctl restart tg-proxy        # перезапустить
journalctl -u tg-proxy -f              # логи
```

Конфигурация сервиса редактируется через `ExecStart`
(порт, host, secret) в `/etc/systemd/system/tg-proxy.service`.

> **Примечание.** GUI и сервис используют один и тот же порт (по умолчанию 1443).
> Не запускайте их одновременно, либо смените порт у одного из них.

## 6. Удаление

```bash
./install.sh uninstall          # если ставили без root (пользовательские файлы)
# или, если ставили системно:
sudo ./install.sh uninstall     # остановит сервис и удалит всё
```

Вручную:

```bash
sudo systemctl stop tg-proxy
sudo systemctl disable tg-proxy
sudo rm /etc/systemd/system/tg-proxy.service
sudo systemctl daemon-reload
rm -f ~/.config/autostart/tg-proxy.desktop
sudo rm -f /usr/share/applications/tg-proxy.desktop /usr/share/pixmaps/tg-proxy.png
sudo rm -rf /opt/tg-proxy
```
