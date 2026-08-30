# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

TG-Proxy is a Telegram MTProto WebSocket proxy with FakeTLS masking, for bypassing blocks on Telegram. It is **two mirrored codebases in one repo**:

- **Desktop (Python)**: `proxy/`, `ui/`, `utils/`, plus top-level `windows.py` / `macos.py` / `linux.py` tray entry points. CustomTkinter + pystray.
- **Android (Kotlin)**: `app/` — Jetpack Compose. The `app/src/main/java/.../proxy/` package mirrors the Python `proxy/` module file-for-file (CryptoCtx, MsgSplitter, RawWebSocket, WsPool, ProxyServer, etc.).

The proxy is a fork of `Flowseal/tg-ws-proxy` and `ihtfw/tg-ws-proxy-android`.

## Commands

Run from the repo root (`TG-Proxy/`).

```bash
# Install desktop deps (editable)
pip install -e .

# Run the proxy server (CLI; default 127.0.0.1:1443)
python -m proxy.tg_ws_proxy            # or `tg-ws-proxy`
python -m proxy.tg_ws_proxy --secret <32-hex> --dc-ip 2:149.154.167.51

# Tests (unittest-based, no pytest config; run manually — CI only builds)
python -m unittest discover -s tests
python -m unittest tests.test_bridge.MsgSplitterTest      # single test case
python -m pytest tests/test_bridge.py                     # pytest also works

# Lint (ruff, config in pyproject.toml; targets py38)
ruff check .

# Build desktop executable (per-platform PyInstaller spec)
pyinstaller packaging/linux.spec     # or windows.spec / macos.spec

# Android
./gradlew assembleDebug              # or assembleRelease

# Desktop bundle via Docker (Dockerfile at repo root)
docker build -t tg-proxy .
```

Version is dynamic: `hatch` reads `__version__` from `proxy/__init__.py`. Tests are **not** wired into CI (`.github/workflows/*` only build and release).

## Architecture

### The relay model (core concept)

Telegram clients speak the obfuscated2 MTProto transport over TCP with a 64-byte handshake. Telegram's official WebSocket endpoints (`kws{dc}.web.telegram.org/apiws`) expect a *differently*-obfuscated stream than what the client sends. The proxy decrypts the client's obfuscation and re-encrypts toward Telegram — it is **not** a transparent pipe; it terminates and rebuilds obfuscation in both directions.

The crypto context is `CryptoCtx` (`proxy/bridge.py`) holding four AES-CTR encryptors: `clt_dec`/`clt_enc` (client side, keyed with `SHA256(prekey + secret)`) and `tg_enc`/`tg_dec` (relay side, raw key from the generated relay init, no secret hash). All keystreams are fast-forwarded past the 64-byte init via `ZERO_64`.

### Connection flow (`proxy/tg_ws_proxy.py`)

`_run` starts an `asyncio.start_server`; each client hits `_handle_client`:

1. `_read_client_init` reads the handshake, optionally stripping FakeTLS (`ee` secret) or a PROXY-protocol header.
2. `_try_handshake` (`proxy/utils.py`) decrypts the handshake to recover DC id, media flag, and transport protocol (abridged / intermediate / padded).
3. `_generate_relay_init` + `_build_crypto_ctx` produce the relay handshake and the `CryptoCtx`.
4. Connect to Telegram WS (pooled or fresh) and call `bridge_ws_reencrypt`, which runs two bidirectional copy tasks, each decrypting one side and re-encrypting for the other.

`MsgSplitter` (`proxy/bridge.py`) splits the encrypted TCP stream into individual MTProto transport packets so each goes out as a single WS frame — required by the WS transport's framing.

### Fallback chain

When the primary WS route fails (blacklisted, timed out, all-302, or DC not configured), `do_fallback` (`proxy/bridge.py`) tries, in order: Cloudflare Worker (`/apiws?dst=...&dc=...`) → Cloudflare-proxied domain (`kws{dc}.{domain}`) → raw TCP to the DC IP on port 443, re-encrypting at each hop. Failure state lives in module globals: `ws_blacklist`, `dc_fail_until`, `ip_fail_until`.

### Module-global singletons

State is shared via module-level instances (not dependency injection). Importing them mutates the same object across the process:

- `proxy.config.proxy_config` — populated from CLI args / UI, holds host, port, secret, `dc_redirects`, fallback settings.
- `proxy.stats.stats` — connection/byte counters.
- `proxy.pool.ws_pool` and `proxy.pool.cf_worker_pool` — WS connection pools with `warmup()`/`get()`/`report_success()`.
- `proxy.balancer.balancer` — rotates/selects Cloudflare domains per DC.

Be aware tests and `_run` reset these globals (`ws_pool.reset()`, `ws_blacklist.clear()`, etc.) at startup.

### Supporting modules

- `proxy/utils.py` — byte-level constants (`PROTO_TAG_*`, `DC_DEFAULT_IPS`, `WS_PATH`), `ws_domains()`, handshake crypto helpers, and a GitHub-IP-pinning HTTPS opener.
- `proxy/fake_tls.py` — FakeTLS (`ee` secret) handshake verification / server-hello construction and the `FakeTlsStream` wrapper.
- `proxy/raw_websocket.py` — asyncio WS client with TLS SNI/IP pinning to the target DC.
- `proxy/_aes.py` — vendored pure-Python AES-CTR (keeps the desktop bundle self-contained).

The Android `proxy/` package implements the same protocol in Kotlin; keep both in sync when changing protocol behavior.

## Notes

- FakeTLS masks traffic as HTTPS to an arbitrary SNI domain; the `SNI_list.txt` file and `--fake-tls-domain` flag drive it. Non-TLS probes get an HTTP 301 redirect to the masking domain.
- Secret format: `dd<secret>` is plain obfuscated2; `ee<secret><domain-hex>` enables FakeTLS.
