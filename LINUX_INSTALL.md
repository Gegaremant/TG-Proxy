# Установка и запуск TG Proxy на Linux

Проект собирает **два** бинарника для Linux:

| Бинарник | Назначение |
|----------|-----------|
| `TG-Proxy-1.2.1-Linux` | GUI-версия с трей-иконкой (для рабочего стола) |
| `TG-Proxy-Service` | Headless CLI-версия (для systemd-сервиса, без GUI) |

## 1. Сборка

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

## 2. Установка

```bash
sudo ./install.sh
```

Скрипт:
- копирует оба бинарника в `/opt/tg-proxy/`;
- устанавливает иконку и пункт меню `TG Proxy`;
- **спрашивает**, включить ли автозапуск GUI при входе в систему;
- **спрашивает**, включить ли headless-прокси как systemd-сервис.

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

Если при установке вы ответили «да», создаётся файл
`~/.config/autostart/tg-proxy.desktop`. Вручную:

```bash
mkdir -p ~/.config/autostart
cp packaging/tg-proxy-autostart.desktop ~/.config/autostart/
```

## 5. Headless-сервис (systemd)

Сначала заполните секретный ключ в файле сервиса
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
sudo systemctl stop tg-proxy
sudo systemctl disable tg-proxy
sudo rm /etc/systemd/system/tg-proxy.service
sudo systemctl daemon-reload
rm -f ~/.config/autostart/tg-proxy.desktop
sudo rm -f /usr/share/applications/tg-proxy.desktop /usr/share/pixmaps/tg-proxy.png
sudo rm -rf /opt/tg-proxy
```
