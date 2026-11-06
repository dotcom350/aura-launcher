"""
Genera los iconos de Aura a partir del set Reicon (MIT, https://reicon.dev) en formato Iconify JSON.

  python -I tools/gen_icons.py <ruta a icons.json de @iconify-json/reicon>

Salidas:
  app/src/main/assets/web/icons.js     -> iconos SVG para el panel web
  app/src/main/res/drawable/ri_*.xml   -> vector drawables para la TV
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

WEB_ICONS = [
    "home", "remote", "grid", "wallpaper", "settings", "power", "power-off", "volume-high", "volume-low",
    "volume-mute", "play", "pause", "rewind", "fast-forward", "skip-next", "skip-prev", "chevron-up",
    "chevron-down", "chevron-left", "chevron-right", "check", "plus", "minus", "trash", "upload", "eye",
    "eye-off", "globe", "thermometer", "logout", "tv", "search", "keyboard", "camera", "refresh", "clock",
    "sun", "moon", "sunrise", "sunset", "cloud-sun", "moon-cloud", "clouds", "cloud-fog", "cloud-rain",
    "cloud-snow", "cloud-bolt", "x-circle", "menu", "category", "information", "image", "star", "download",
    "arrow-left", "moon-stars", "text", "layout", "palette", "wifi", "display", "gamepad", "accessibility",
    "sliders", "arrow-up-right",
    "shield-check", "chart", "ban", "shield", "data", "bell-off", "video", "volume-cross", "moon-sleep", "soundwave", "broom", "sparkles", "film", "link",
]
TV_ICONS = [
    "sun", "moon", "cloud-sun", "moon-cloud", "clouds", "cloud-fog", "cloud-rain", "cloud-snow",
    "cloud-bolt", "settings", "grid", "category", "wifi", "remote", "home", "wallpaper", "globe",
    "thermometer", "clock", "text", "layout", "eye", "tv", "chevron-left", "chevron-right", "information",
    "refresh", "check", "star", "palette", "display", "gamepad", "accessibility", "sliders",
    "shield-check", "eye-off", "chart", "ban", "shield", "data", "bell-off", "video", "volume-cross", "moon-sleep", "soundwave", "broom", "sparkles", "film", "power", "link", "play",
]


def rect_path(x, y, w, h, rx):
    if rx <= 0:
        return f"M{x},{y}h{w}v{h}h{-w}z"
    rx = min(rx, w / 2, h / 2)
    return (f"M{x + rx},{y}h{w - 2 * rx}a{rx},{rx} 0 0 1 {rx},{rx}v{h - 2 * rx}a{rx},{rx} 0 0 1 {-rx},{rx}"
            f"h{-(w - 2 * rx)}a{rx},{rx} 0 0 1 {-rx},{-rx}v{-(h - 2 * rx)}a{rx},{rx} 0 0 1 {rx},{-rx}z")


def circle_path(cx, cy, rx, ry):
    return f"M{cx - rx},{cy}a{rx},{ry} 0 1 0 {2 * rx},0a{rx},{ry} 0 1 0 {-2 * rx},0z"


SKIP = {"defs", "clipPath", "mask", "title"}
STYLE_KEYS = ("fill", "fill-rule", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin")


def collect(el, inherited, out):
    """Recorre el SVG heredando estilos. Ignora defs/clipPath (antes salian como un cuadrado relleno)."""
    tag = el.tag.split("}")[-1]
    if tag in SKIP:
        return
    style = dict(inherited)
    for k in STYLE_KEYS:
        if el.get(k) is not None:
            style[k] = el.get(k)
    f = lambda k, d=0.0: float(el.get(k, d))  # noqa: E731
    d = None
    if tag == "path":
        d = el.get("d")
    elif tag == "rect":
        d = rect_path(f("x"), f("y"), f("width"), f("height"), f("rx", el.get("ry", 0)))
    elif tag == "circle":
        d = circle_path(f("cx"), f("cy"), f("r"), f("r"))
    elif tag == "ellipse":
        d = circle_path(f("cx"), f("cy"), f("rx"), f("ry"))
    if d:
        out.append((d, style))
    for child in el:
        collect(child, style, out)


def to_vector(body, size):
    root = ET.fromstring(f'<svg xmlns="http://www.w3.org/2000/svg">{body}</svg>')
    paths = []
    collect(root, {"fill": "currentColor", "fill-rule": "nonzero"}, paths)
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Reicon (MIT) https://reicon.dev -->",
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" '
        f'android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">',
    ]
    for d, st in paths:
        attrs = []
        if st.get("fill", "currentColor") != "none":
            attrs.append('android:fillColor="#FFFFFFFF"')
            if st.get("fill-rule") == "evenodd":
                attrs.append('android:fillType="evenOdd"')
        if st.get("stroke") not in (None, "none"):
            attrs.append('android:strokeColor="#FFFFFFFF"')
            attrs.append(f'android:strokeWidth="{st.get("stroke-width", "1")}"')
            cap = st.get("stroke-linecap")
            if cap in ("round", "square", "butt"):
                attrs.append(f'android:strokeLineCap="{cap}"')
            join = st.get("stroke-linejoin")
            if join in ("round", "bevel", "miter"):
                attrs.append(f'android:strokeLineJoin="{join}"')
        if not attrs:
            continue
        lines.append(f'    <path {" ".join(attrs)} android:pathData="{d}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def main(src):
    data = json.load(open(src, encoding="utf-8"))
    icons, size = data["icons"], data.get("width", 24)

    web = {}
    for name in WEB_ICONS:
        body = icons[name]["body"]
        web[name] = f'<svg viewBox="0 0 {size} {size}" aria-hidden="true">{body}</svg>'
    js = ("/* Iconos Reicon (MIT) https://reicon.dev - generado por tools/gen_icons.py */\n"
          "window.ICONS = " + json.dumps(web, ensure_ascii=False) + ";\n")
    open(os.path.join(ROOT, "app/src/main/assets/web/icons.js"), "w", encoding="utf-8").write(js)

    out_dir = os.path.join(ROOT, "app/src/main/res/drawable")
    for name in TV_ICONS:
        res = "ri_" + re.sub(r"[^a-z0-9]", "_", name)
        open(os.path.join(out_dir, res + ".xml"), "w", encoding="utf-8").write(to_vector(icons[name]["body"], size))
    print(f"web: {len(web)} iconos, tv: {len(TV_ICONS)} drawables")


if __name__ == "__main__":
    main(sys.argv[1])
