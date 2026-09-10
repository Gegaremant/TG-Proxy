# -*- mode: python ; coding: utf-8 -*-
# Headless CLI proxy binary for systemd serivce (no GUI/tray).

import os

block_cipher = None

a = Analysis(
    [os.path.join(os.path.dirname(SPEC), os.pardir, 'proxy', 'tg_ws_proxy.py')],
    pathex=[os.path.join(os.path.dirname(SPEC), os.pardir)],
    binaries=[],
    datas=[],
    hiddenimports=[
        'cryptography.hazmat.primitives.ciphers',
        'cryptography.hazmat.primitives.ciphers.algorithms',
        'cryptography.hazmat.primitives.ciphers.modes',
        'cryptography.hazmat.backends.openssl',
        'certifi',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', 'customtkinter', 'pystray', 'PIL', 'pyperclip'],
    noarchive=False,
    cipher=block_cipher,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='TG-Proxy-Service',
    debug=False,
    bootloader_ignore_signals=False,
    strip=True,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
