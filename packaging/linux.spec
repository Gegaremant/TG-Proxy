# -*- mode: python ; coding: utf-8 -*-

import sys
import os
import glob

from PyInstaller.utils.hooks import collect_submodules, collect_data_files

block_cipher = None

# customtkinter ships JSON themes + assets that must be bundled
import customtkinter
ctk_path = os.path.dirname(customtkinter.__file__)
certifi_datas = collect_data_files('certifi')

_i18n_path = os.path.join(os.path.dirname(SPEC), os.pardir, 'ui', 'i18n')

# Collect gi (PyGObject) submodules and data so pystray._appindicator works
gi_hiddenimports = collect_submodules('gi')
gi_datas = collect_data_files('gi')

# Collect GObject typelib files from the system
typelib_dirs = glob.glob('/usr/lib/*/girepository-1.0')
typelib_datas = []
for d in typelib_dirs:
    typelib_datas.append((d, 'gi_typelibs'))

# --- Tcl/Tk runtime libraries + scripts required by the bundled _tkinter ---
# _tkinter links against libtcl9.0.so / libtcl9tk9.0.so and loads init scripts
# (init.tcl, tk.tcl, ...) from the Tcl/Tk version directories at runtime.
_tcl_libs = []
_tcl_datas = []
try:
    import _tkinter as _tkl
    # _tkinter lives at <prefix>/lib/pythonX.Y/lib-dynload/_tkinter.*.so
    _tcl_base = os.path.join(
        os.path.dirname(_tkl.__file__), os.pardir, os.pardir, os.pardir
    )
except Exception:
    _tkl = None
    _tcl_base = os.path.join(os.path.dirname(sys.executable), os.pardir, 'lib')

_tcl_base = os.path.normpath(_tcl_base)
_tcl_libdir = os.path.join(_tcl_base, 'lib')

for _lib in ['libtcl9.0.so', 'libtcl9tk9.0.so']:
    _p = os.path.join(_tcl_libdir, _lib)
    if os.path.exists(_p):
        _tcl_libs.append((_p, '.'))

for _tdir in ['tcl9.0', 'tk9.0', 'tcl9']:
    _d = os.path.join(_tcl_libdir, _tdir)
    if os.path.isdir(_d):
        _tcl_datas.append((_d, _tdir))

a = Analysis(
    [os.path.join(os.path.dirname(SPEC), os.pardir, 'linux.py')],
    pathex=[],
    binaries=_tcl_libs,
    datas=[(ctk_path, 'customtkinter/'), (_i18n_path, 'ui/i18n')] + certifi_datas + gi_datas + typelib_datas + _tcl_datas,
    hiddenimports=[
        'pystray._appindicator',
        'pystray._xorg',
        'Xlib',
        'Xlib.display',
        'PIL._tkinter_finder',
        'customtkinter',
        'cryptography.hazmat.primitives.ciphers',
        'cryptography.hazmat.primitives.ciphers.algorithms',
        'cryptography.hazmat.primitives.ciphers.modes',
        'cryptography.hazmat.backends.openssl',
        'gi',
        '_gi',
        'gi.repository.GLib',
        'gi.repository.GObject',
        'gi.repository.Gtk',
        'gi.repository.Gdk',
        'gi.repository.AyatanaAppIndicator3',
    ] + gi_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'PIL._avif',
        'PIL._webp',
        'PIL._imagingtk',
    ],
    noarchive=False,
    cipher=block_cipher,
)

_PIL_EXCLUDE_PYDS = {
    '_avif', '_webp', '_imagingtk',
    'FpxImagePlugin', 'MicImagePlugin',
}
a.binaries = [
    (name, path, typ)
    for name, path, typ in a.binaries
    if not any(ex in name for ex in _PIL_EXCLUDE_PYDS)
]

icon_path = os.path.join(os.path.dirname(SPEC), os.pardir, 'icon.ico')
if os.path.exists(icon_path):
    a.datas += [('icon.ico', icon_path, 'DATA')]

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='TG-Proxy-1.2.1-Linux',
    debug=False,
    bootloader_ignore_signals=False,
    strip=True,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
