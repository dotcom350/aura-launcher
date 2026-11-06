#!/usr/bin/env python3
"""
Servidor MCP (stdio) para controlar el Fire TV con el launcher Aura.
Sin dependencias: solo la biblioteca estandar de Python.

Variables de entorno:
  AURA_HOST   IP del Fire TV            (por defecto 10.0.0.4)
  AURA_PORT   Puerto de la web de Aura  (por defecto 8765)
  AURA_TOKEN  Token de acceso (en la TV: boton Menu)
  AURA_PIN    Codigo de seguridad, solo si se activo en la web (Ajustes > Seguridad)
  ADB_PATH    adb.exe del PC, para encender la TV si esta dormida
"""
import base64
import difflib
import json
import os
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request

HOST = os.environ.get("AURA_HOST", "10.0.0.4")
PORT = int(os.environ.get("AURA_PORT", "8765"))
TOKEN = os.environ.get("AURA_TOKEN", "")
PIN = os.environ.get("AURA_PIN", "")
SESSION = ""
ADB = os.environ.get("ADB_PATH", r"C:\platform-tools\adb.exe")
BASE = f"http://{HOST}:{PORT}"

KEYS = ["up", "down", "left", "right", "ok", "back", "home", "menu", "playpause", "play", "pause",
        "rewind", "forward", "next", "previous", "volup", "voldown", "mute"]
# Respaldo por ADB del PC (Fire OS no permite ADB desde la propia TV, pero si desde el PC)
ADB_KEYCODES = {
    "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN", "left": "KEYCODE_DPAD_LEFT",
    "right": "KEYCODE_DPAD_RIGHT", "ok": "KEYCODE_DPAD_CENTER", "back": "KEYCODE_BACK",
    "home": "KEYCODE_HOME", "menu": "KEYCODE_MENU", "playpause": "KEYCODE_MEDIA_PLAY_PAUSE",
    "play": "KEYCODE_MEDIA_PLAY", "pause": "KEYCODE_MEDIA_PAUSE", "rewind": "KEYCODE_MEDIA_REWIND",
    "forward": "KEYCODE_MEDIA_FAST_FORWARD", "next": "KEYCODE_MEDIA_NEXT",
    "previous": "KEYCODE_MEDIA_PREVIOUS", "volup": "KEYCODE_VOLUME_UP",
    "voldown": "KEYCODE_VOLUME_DOWN", "mute": "KEYCODE_VOLUME_MUTE",
}

ALIASES = {  # nombres hablados -> texto a buscar en las apps
    "proton": "proton vpn", "vpn": "proton vpn", "disney": "disney+", "youtube": "youtube",
    "netflix": "netflix", "xuper": "xuper", "smart tube": "smarttube", "jw": "jw broadcasting",
    "ajustes": "ajustes", "configuracion": "ajustes",
}


# ---------------------------------------------------------------- API de Aura
def _request(path, body, timeout):
    data = None if body is None else json.dumps(body).encode()
    headers = {"X-Token": TOKEN, "Content-Type": "application/json"}
    if SESSION:
        headers["X-Session"] = SESSION
    req = urllib.request.Request(BASE + path, data=data, method="GET" if body is None else "POST", headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def _login():
    """Con codigo de seguridad activado en Aura, pide una sesion con AURA_PIN."""
    global SESSION
    if not PIN:
        raise RuntimeError("Aura tiene codigo de seguridad: anade AURA_PIN en .mcp.json")
    SESSION = ""
    SESSION = json.loads(_request("/api/login", {"pin": PIN}, 8)).get("session", "")


def api(path, body=None, raw=False, timeout=8):
    try:
        payload = _request(path, body, timeout)
    except urllib.error.HTTPError as e:
        if e.code != 401 or b"pin_required" not in e.read():
            raise
        _login()
        payload = _request(path, body, timeout)
    return payload if raw else json.loads(payload)


def adb(*args, timeout=15):
    """Ejecuta adb del PC con argumentos fijos (nunca a traves de un shell)."""
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)


def adb_key(keycode):
    adb("connect", f"{HOST}:5555")
    return adb("-s", f"{HOST}:5555", "shell", "input", "keyevent", keycode)


def norm(text):
    text = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in text if not unicodedata.combining(c)).strip()


def find_app(name):
    state = api("/api/state")
    apps = state["apps"] + [{"pkg": "aura.settings", "label": "Ajustes"}]
    wanted = norm(ALIASES.get(norm(name), name))
    labels = {norm(a["label"]): a for a in apps}
    if wanted in labels:
        return labels[wanted]
    for label, app in labels.items():
        if wanted in label or label in wanted:
            return app
    close = difflib.get_close_matches(wanted, list(labels), n=1, cutoff=0.5)
    return labels[close[0]] if close else None


# ---------------------------------------------------------------- herramientas
def t_estado(_):
    s = api("/api/state")
    labels = {a["pkg"]: a["label"] for a in s["apps"]}
    lines = [
        f"Aura en http://{s.get('ip')}:{s.get('port')}",
        "Fila principal: " + ", ".join(labels.get(p, "Ajustes") for p in s["pinned"]),
        f"App enfocada: {labels.get(s.get('focused'), s.get('focused'))}",
        f"Fondo: {s.get('currentWallpaper')} (modo {s['config']['wallpaperMode']})",
    ]
    if s.get("weather"):
        w = s["weather"]
        lines.append(f"Clima: {w['temp']}°{w.get('unit', 'C')} {w['description']} en {w['place']}")
    return lines


def t_encender(_):
    try:
        api("/api/power", {"action": "on"})
        return ["TV encendida (via Aura)."]
    except Exception:
        r = adb_key("KEYCODE_WAKEUP")
        if r.returncode == 0:
            return ["La TV no respondia a la web; encendida via ADB desde el PC."]
        raise RuntimeError("No se pudo encender: " + (r.stderr or r.stdout).strip())


def t_apagar(_):
    try:
        api("/api/power", {"action": "off"})
    except Exception:
        adb_key("KEYCODE_SLEEP")
    return ["TV apagada."]


def press(key, times):
    """Prueba la API de Aura; si no funciona (p. ej. 'menu' o una app no accesible), usa ADB del PC."""
    times = max(1, min(int(times), 20))
    if key != "menu":
        try:
            if api("/api/key", {"key": key, "times": times}).get("ok"):
                return "Aura"
        except Exception:  # noqa: BLE001
            pass
    for _ in range(times):
        r = adb_key(ADB_KEYCODES[key])
        if r.returncode != 0:
            raise RuntimeError("No se pudo pulsar la tecla: " + (r.stderr or r.stdout).strip())
    return "ADB del PC"


def t_tecla(args):
    key = args.get("tecla", "")
    if key not in KEYS:
        raise ValueError(f"Tecla no valida. Opciones: {', '.join(KEYS)}")
    via = press(key, args.get("veces", 1))
    return [f"Pulsada '{key}' x{args.get('veces', 1)} (via {via})."]


def t_volumen(args):
    key = {"subir": "volup", "bajar": "voldown", "silencio": "mute"}.get(args.get("accion", ""))
    if not key:
        raise ValueError("accion debe ser subir, bajar o silencio")
    press(key, args.get("veces", 2))
    return [f"Volumen: {args['accion']}."]


def t_abrir_app(args):
    app = find_app(args.get("nombre", ""))
    if not app:
        raise ValueError(f"No encuentro una app parecida a '{args.get('nombre')}'.")
    res = api("/api/launch", {"pkg": app["pkg"]})
    return [f"Abriendo {app['label']}." if res.get("ok") else f"No se pudo abrir {app['label']}."]


def t_listar_apps(_):
    s = api("/api/state")
    labels = {a["pkg"]: a["label"] for a in s["apps"]}
    return ["Fila principal: " + ", ".join(labels.get(p, "Ajustes") for p in s["pinned"]),
            "Otras: " + ", ".join(labels[p] for p in s["others"] if p in labels)]


def t_escribir(args):
    api("/api/text", {"text": args.get("texto", "")})
    return ["Texto enviado."]


def t_modo_fondo(args):
    mode = {"hora": "time", "rotar": "rotate", "fijo": "fixed"}.get(args.get("modo", ""))
    if not mode:
        raise ValueError("modo debe ser hora, rotar o fijo")
    api("/api/config", {"wallpaperMode": mode})
    return [f"Modo de fondo: {args['modo']}."]


def t_captura(_):
    png = api("/api/screenshot", raw=True, timeout=20)
    return [{"type": "image", "data": base64.b64encode(png).decode(), "mimeType": "image/png"}]


TOOLS = {
    "tv_estado": (t_estado, "Estado de la TV: apps, app enfocada, fondo y clima.", {}),
    "tv_encender": (t_encender, "Enciende la TV (y la tele por HDMI-CEC).", {}),
    "tv_apagar": (t_apagar, "Apaga (suspende) la TV.", {}),
    "tv_tecla": (t_tecla, "Pulsa una tecla del mando.", {
        "tecla": {"type": "string", "enum": KEYS},
        "veces": {"type": "integer", "minimum": 1, "maximum": 20, "default": 1}}),
    "tv_volumen": (t_volumen, "Sube, baja o silencia el volumen.", {
        "accion": {"type": "string", "enum": ["subir", "bajar", "silencio"]},
        "veces": {"type": "integer", "minimum": 1, "maximum": 20, "default": 2}}),
    "tv_abrir_app": (t_abrir_app, "Abre una app por su nombre (p. ej. Netflix, Xuper, YouTube).", {
        "nombre": {"type": "string"}}),
    "tv_listar_apps": (t_listar_apps, "Lista las apps de la TV.", {}),
    "tv_escribir": (t_escribir, "Escribe texto en el campo activo (buscadores).", {
        "texto": {"type": "string"}}),
    "tv_modo_fondo": (t_modo_fondo, "Cambia como se elige el fondo de pantalla.", {
        "modo": {"type": "string", "enum": ["hora", "rotar", "fijo"]}}),
    "tv_captura": (t_captura, "Hace una captura de lo que se ve en la TV.", {}),
}
REQUIRED = {"tv_tecla": ["tecla"], "tv_volumen": ["accion"], "tv_abrir_app": ["nombre"],
            "tv_escribir": ["texto"], "tv_modo_fondo": ["modo"]}


# ---------------------------------------------------------------- JSON-RPC por stdio
def send(msg):
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle(msg):
    method, mid, params = msg.get("method"), msg.get("id"), msg.get("params") or {}
    if mid is None:
        return  # notificacion
    if method == "initialize":
        result = {"protocolVersion": params.get("protocolVersion", "2025-06-18"),
                  "capabilities": {"tools": {}},
                  "serverInfo": {"name": "aura-tv", "version": "1.0.0"}}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": [{"name": n, "description": d,
                             "inputSchema": {"type": "object", "properties": p, "required": REQUIRED.get(n, [])}}
                            for n, (_, d, p) in TOOLS.items()]}
    elif method == "tools/call":
        name = params.get("name")
        if name not in TOOLS:
            return send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": f"Herramienta desconocida: {name}"}})
        try:
            out = TOOLS[name][0](params.get("arguments") or {})
            content = [o if isinstance(o, dict) else {"type": "text", "text": o} for o in out]
            result = {"content": content, "isError": False}
        except urllib.error.HTTPError as e:
            msg_ = "Token incorrecto (revisa AURA_TOKEN)" if e.code == 401 else f"HTTP {e.code}"
            result = {"content": [{"type": "text", "text": msg_}], "isError": True}
        except Exception as e:  # noqa: BLE001
            result = {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}
    else:
        return send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Metodo no soportado: {method}"}})
    send({"jsonrpc": "2.0", "id": mid, "result": result})


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    for line in sys.stdin:
        line = line.strip().lstrip("﻿")
        if not line:
            continue
        try:
            handle(json.loads(line))
        except Exception as e:  # noqa: BLE001
            sys.stderr.write(f"aura-mcp: {e}\n")


if __name__ == "__main__":
    main()
