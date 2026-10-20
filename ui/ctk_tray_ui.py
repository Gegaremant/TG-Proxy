from __future__ import annotations

from typing import Any, Callable, Tuple

from proxy import get_link_host
from ui.ctk_controls import create_checkbox
from ui.ctk_theme import FIRST_RUN_FRAME_PAD, CtkTheme, main_content_frame
from ui.ctk_tooltip import attach_ctk_tooltip
from ui.i18n import t
from ui.settings_form import (
    TrayConfigFormWidgets as TrayConfigFormWidgets,
    install_tray_config_form as install_tray_config_form,
    validate_config_form as validate_config_form,
)


def tray_settings_scroll_and_footer(
    ctk: Any,
    content_parent: Any,
    theme: CtkTheme,
) -> Tuple[Any, Any]:
    footer = ctk.CTkFrame(content_parent, fg_color=theme.bg)
    footer.pack(side="bottom", fill="x")
    scroll = ctk.CTkScrollableFrame(
        content_parent,
        fg_color=theme.bg,
        corner_radius=0,
        scrollbar_button_color=theme.field_border,
        scrollbar_button_hover_color=theme.text_secondary,
    )
    scroll.pack(fill="both", expand=True)
    try:
        scroll._parent_canvas.configure(yscrollincrement=4)
    except Exception:
        pass
    return scroll, footer


def install_tray_config_buttons(
    ctk: Any,
    frame: Any,
    theme: CtkTheme,
    *,
    on_save: Callable[[], None],
    on_cancel: Callable[[], None],
) -> None:
    ctk.CTkFrame(
        frame,
        fg_color=theme.field_border,
        height=1,
        corner_radius=0,
    ).pack(fill="x", pady=(4, 10))
    btn_frame = ctk.CTkFrame(frame, fg_color="transparent")
    btn_frame.pack(fill="x", pady=(0, 0))
    save_btn = ctk.CTkButton(
        btn_frame, text=t("button.save"), height=38,
        font=(theme.ui_font_family, 14, "bold"), corner_radius=10,
        fg_color=theme.tg_blue, hover_color=theme.tg_blue_hover,
        text_color="#ffffff",
        command=on_save)
    save_btn.pack(side="left", fill="x", expand=True, padx=(0, 8))
    attach_ctk_tooltip(save_btn, t("tip.save"))
    cancel_btn = ctk.CTkButton(
        btn_frame, text=t("button.cancel"), height=38,
        font=(theme.ui_font_family, 14), corner_radius=10,
        fg_color=theme.field_bg, hover_color=theme.field_border,
        text_color=theme.text_primary, border_width=1,
        border_color=theme.field_border,
        command=on_cancel)
    cancel_btn.pack(side="right", fill="x", expand=True)
    attach_ctk_tooltip(cancel_btn, t("tip.cancel"))


def check_tg_api_available(timeout: float = 5.0) -> bool:
    """Check that Telegram WebSocket API endpoints are reachable (TLS handshake)."""
    import socket as _socket
    import ssl

    for host in ("kws2.web.telegram.org", "149.154.167.220"):
        try:
            with _socket.create_connection((host, 443), timeout=timeout) as raw:
                ctx = ssl.create_default_context()
                with ctx.wrap_socket(raw, server_hostname=host) as ssock:
                    ssock.settimeout(timeout)
                    return True
        except Exception:
            continue
    return False


def populate_first_run_window(
    ctk: Any,
    root: Any,
    theme: CtkTheme,
    *,
    host: str,
    port: int,
    secret: str,
    on_done: Callable[[bool], None],
    check_available: bool = True,
) -> None:
    link_host = get_link_host(host)
    tg_url = f"tg://proxy?server={link_host}&port={port}&secret=dd{secret}"
    fpx, fpy = FIRST_RUN_FRAME_PAD
    frame = main_content_frame(ctk, root, theme, padx=fpx, pady=fpy)

    title_frame = ctk.CTkFrame(frame, fg_color="transparent")
    title_frame.pack(anchor="w", pady=(0, 16), fill="x")

    accent_bar = ctk.CTkFrame(title_frame, fg_color=theme.tg_blue,
                              width=4, height=32, corner_radius=2)
    accent_bar.pack(side="left", padx=(0, 12))

    ctk.CTkLabel(title_frame, text=t("first_run.title"),
                 font=(theme.ui_font_family, 17, "bold"),
                 text_color=theme.text_primary).pack(side="left")

    sections = [
        (t("first_run.how_to"), True),
        (t("first_run.auto"), True),
        (t("first_run.auto_hint"), False),
        (t("first_run.auto_link", url=tg_url), False),
        ("\n" + t("first_run.manual"), True),
        (t("first_run.manual_path"), False),
        (t("first_run.manual_mtproto", host=link_host, port=port), False),
        (t("first_run.manual_secret", secret=secret), False),
    ]

    textbox = ctk.CTkTextbox(
        frame,
        font=(theme.ui_font_family, 13),
        fg_color=theme.bg,
        border_width=0,
        text_color=theme.text_primary,
        activate_scrollbars=False,
        wrap="word",
        height=275,
    )
    textbox._textbox.tag_configure("bold", font=(theme.ui_font_family, 13, "bold"))
    textbox._textbox.configure(spacing1=1, spacing3=1)
    textbox.pack(anchor="w", fill="x", expand=True)

    def _fill(sections_: list) -> None:
        textbox.configure(state="normal")
        textbox.delete("1.0", "end")
        for text, bold in sections_:
            if text.startswith("\n"):
                textbox.insert("end", "\n")
                text = text[1:]
            if bold:
                textbox.insert("end", text + "\n", "bold")
            else:
                textbox.insert("end", text + "\n")
        textbox.configure(state="disabled")

    def _render_checking() -> None:
        _fill([(t("first_run.checking"), False)])

    def _render_links() -> None:
        retry_btn.pack_forget()
        _fill(sections)

    def _render_fail() -> None:
        _fill([(t("first_run.check_fail"), False)])
        retry_btn.pack(side="left", padx=(0, 8))

    def _start_check() -> None:
        import threading
        _render_checking()

        def _worker() -> None:
            ok = check_tg_api_available()
            root.after(0, _render_links if ok else _render_fail)

        threading.Thread(target=_worker, daemon=True, name="tg-api-check").start()

    ctk.CTkFrame(frame, fg_color="transparent", height=16).pack()

    ctk.CTkFrame(frame, fg_color=theme.field_border, height=1,
                 corner_radius=0).pack(fill="x", pady=(0, 12))

    auto_var = ctk.BooleanVar(value=True)
    create_checkbox(ctk, frame, theme, t("first_run.open_now"),
              auto_var).pack(anchor="w", pady=(0, 16))

    def on_ok():
        on_done(auto_var.get())

    btn_row = ctk.CTkFrame(frame, fg_color="transparent")
    btn_row.pack(fill="x")
    retry_btn = ctk.CTkButton(
        btn_row, text=t("first_run.check_retry"), width=160, height=42,
        font=(theme.ui_font_family, 13), corner_radius=10,
        fg_color=theme.field_bg, hover_color=theme.field_border,
        text_color=theme.text_primary, border_width=1,
        border_color=theme.field_border,
        command=_start_check)
    start_btn = ctk.CTkButton(
        btn_row, text=t("button.start"), width=180, height=42,
        font=(theme.ui_font_family, 15, "bold"), corner_radius=10,
        fg_color=theme.tg_blue, hover_color=theme.tg_blue_hover,
        text_color="#ffffff",
        command=on_ok)
    start_btn.pack(side="left")
    if check_available:
        _start_check()
    else:
        _render_links()

    root.protocol("WM_DELETE_WINDOW", on_ok)
