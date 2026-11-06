#!/usr/bin/env python3
"""
Genera landing/release.js (datos de la ultima version para la landing) y, opcionalmente, las notas de la version.

Lo usan scripts/update-landing.ps1 (en el PC) y .github/workflows/release.yml (en GitHub).

  python scripts/release_info.py --apk ruta/al.apk [--file-url URL] [--latest-url URL] [--notes-out notas.md]

- La version sale de version.properties.
- Las novedades salen de CHANGELOG.md: la seccion "## [X.Y]" de esa version (o "## [Sin publicar]" si no existe).
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def version():
    props = {}
    with open(os.path.join(ROOT, "version.properties"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props["versionName"], int(props["versionCode"])


def changelog_section(name):
    """Lineas de la seccion de esa version en CHANGELOG.md (sin el titulo)."""
    path = os.path.join(ROOT, "CHANGELOG.md")
    if not os.path.exists(path):
        return []
    text = open(path, encoding="utf-8").read()
    for wanted in (re.escape(name), "Sin publicar"):
        m = re.search(r"^## \[" + wanted + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.S | re.M)
        if m and m.group(1).strip():
            return [l.rstrip() for l in m.group(1).strip().splitlines()]
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--file-url", help="Enlace al APK de esta version (por defecto downloads/Aura-X.apk)")
    ap.add_argument("--latest-url", help="Enlace fijo a la ultima version (por defecto downloads/Aura-latest.apk)")
    ap.add_argument("--out", default=os.path.join(ROOT, "landing", "release.js"))
    ap.add_argument("--notes-out", help="Guarda las novedades en Markdown (para la Release de GitHub)")
    a = ap.parse_args()

    name, code = version()
    data = open(a.apk, "rb").read()
    lines = changelog_section(name)
    bullets = [re.sub(r"^[-*]\s+", "", l) for l in lines if re.match(r"^[-*]\s+", l)]
    built = dt.datetime.fromtimestamp(os.path.getmtime(a.apk))

    release = {
        "version": name,
        "code": code,
        "file": a.file_url or f"downloads/Aura-{name}.apk",
        "latest": a.latest_url or "downloads/Aura-latest.apk",
        "sizeMb": round(len(data) / 1024 / 1024, 1),
        "sha256": hashlib.sha256(data).hexdigest(),
        "date": built.strftime("%Y-%m-%d"),
        "built": built.strftime("%Y-%m-%d %H:%M"),
        "notes": bullets,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("// Generado por scripts/release_info.py - no editar a mano\n")
        f.write("window.AURA_RELEASE = " + json.dumps(release, ensure_ascii=False, indent=2) + ";\n")

    if a.notes_out:
        body = "\n".join(lines) if lines else "- Sin notas."
        body += f"\n\n**SHA-256** `{release['sha256']}` · {release['sizeMb']} MB · versionCode {code}\n"
        with open(a.notes_out, "w", encoding="utf-8") as f:
            f.write(body)
    print(f"Aura {name} (code {code}), {release['sizeMb']} MB -> {a.out}")


if __name__ == "__main__":
    main()
