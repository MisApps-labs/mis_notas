#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 ferlagod
# Mis Notas — https://github.com/MisApps-labs/mis_notas
"""
misnotas-decrypt: descifra la carpeta de Mis Notas de tu servidor y la exporta a Markdown.

Sirve para no depender de la app: con tu contraseña maestra (o la clave de recuperación de una
bóveda v3) obtienes todas las notas como ficheros .md con sus carpetas y adjuntos, en el mismo
formato que la copia de seguridad de la app (se pueden volver a importar en Mis Notas, Obsidian…).

Uso:
  # Carpeta ya descargada (p. ej. con el cliente de Nextcloud):
  python3 misnotas_decrypt.py --dir ~/Nextcloud/MisNotas --out ./notas

  # Directamente por WebDAV:
  python3 misnotas_decrypt.py --url https://nube.ejemplo.com/remote.php/dav/files/ana/MisNotas \\
      --user ana --out ./notas

La contraseña maestra se pide por teclado (o en la variable MISNOTAS_PASSWORD); la del servidor,
igual (MISNOTAS_DAV_PASSWORD). Nada sale de tu equipo salvo las descargas del propio servidor.

Formato: docs/SYNC_FORMAT.md. Requisitos: pip install -r requirements.txt
"""

import argparse
import base64
import getpass
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

try:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
except ImportError:  # pragma: no cover
    sys.exit("Falta la librería 'cryptography': pip install -r requirements.txt")

LEGACY_KID = "v2"
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


class DecryptError(Exception):
    pass


# ----------------------------------------------------------------------
# Criptografía
# ----------------------------------------------------------------------

def b64d(s):
    return base64.b64decode(s)


def gcm_decrypt(blob, key, aad=None):
    """Bloque IV(12) ‖ texto cifrado ‖ etiqueta(16), como CryptoManager."""
    if len(blob) < 28:
        raise DecryptError("bloque demasiado corto")
    try:
        return AESGCM(key).decrypt(blob[:12], blob[12:], aad.encode() if aad is not None else None)
    except Exception as e:
        raise DecryptError("no se pudo descifrar (clave o datos incorrectos)") from e


def gcm_decrypt_text(b64, key, aad=None):
    return gcm_decrypt(b64d(b64), key, aad).decode("utf-8")


def password_variants(pw):
    nfc = unicodedata.normalize("NFC", pw)
    nfd = unicodedata.normalize("NFD", pw)
    out = []
    for v in (pw, nfc, nfd, pw.strip(), nfc.strip(), nfd.strip()):
        if v and v not in out:
            out.append(v)
    return out


def pbkdf2(pw, salt, iterations):
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=iterations).derive(pw.encode("utf-8"))


def argon2id(pw, salt, kdf):
    try:
        from argon2.low_level import Type, hash_secret_raw
    except ImportError:
        sys.exit("Esta bóveda es v3 y necesita 'argon2-cffi': pip install -r requirements.txt")
    return hash_secret_raw(
        pw.encode("utf-8"), salt,
        time_cost=kdf["iterations"], memory_cost=kdf["memoryKiB"], parallelism=kdf["parallelism"],
        hash_len=32, type=Type.ID, version=19,
    )


def parse_recovery_key(text):
    clean = [c for c in text.upper() if not c.isspace() and c != "-"]
    clean = ["O" if c == "0" else "I" if c == "1" else "B" if c == "8" else c for c in clean]
    if len(clean) != 32:
        raise DecryptError("la clave de recuperación debe tener 32 caracteres")
    bits = 0
    buf = 0
    out = bytearray()
    for c in clean:
        v = B32.find(c)
        if v < 0:
            raise DecryptError("carácter no válido en la clave de recuperación")
        buf = (buf << 5) | v
        bits += 5
        if bits >= 8:
            out.append((buf >> (bits - 8)) & 0xFF)
            bits -= 8
    return bytes(out[:20])


# ----------------------------------------------------------------------
# Bóveda
# ----------------------------------------------------------------------

def open_vault(keyinfo_raw, password=None, recovery=None):
    """Devuelve el llavero {kid: clave} y el kid actual."""
    info = json.loads(keyinfo_raw)
    if info.get("format") != "misnotas-keyinfo":
        raise DecryptError("keyinfo.json no válido")
    version = info.get("version", 1)
    if version >= 3:
        vmk = None
        if recovery:
            if "recovery" not in info:
                raise DecryptError("esta bóveda no tiene clave de recuperación")
            rk = HKDF(algorithm=hashes.SHA256(), length=32, salt=b64d(info["recoverySalt"]), info=b"misnotas-recovery-v1").derive(
                parse_recovery_key(recovery))
            vmk = b64d(gcm_decrypt_text(info["recovery"], rk, "vmk:recovery"))
        else:
            kdf = info["kdf"]
            if kdf.get("name") != "argon2id":
                raise DecryptError("algoritmo de derivación no soportado")
            for variant in password_variants(password):
                kek = argon2id(variant, b64d(kdf["salt"]), kdf)
                try:
                    vmk = b64d(gcm_decrypt_text(info["password"], kek, "vmk:password"))
                    break
                except DecryptError:
                    continue
            if vmk is None:
                raise DecryptError("contraseña maestra incorrecta")
        ring = json.loads(gcm_decrypt_text(info["keyring"], vmk, "keyring"))
        return {k: b64d(v) for k, v in ring["keys"].items()}, ring["current"]

    if recovery:
        raise DecryptError("las bóvedas v2 no tienen clave de recuperación")
    if info.get("kdf", "PBKDF2WithHmacSHA256") != "PBKDF2WithHmacSHA256":
        raise DecryptError("algoritmo de derivación no soportado")
    salt = b64d(info["salt"])
    for variant in password_variants(password):
        key = pbkdf2(variant, salt, int(info["iterations"]))
        try:
            if gcm_decrypt_text(info["check"], key, "keyinfo") == "misnotas-key-check-v1":
                return {LEGACY_KID: key}, LEGACY_KID
        except DecryptError:
            continue
    raise DecryptError("contraseña maestra incorrecta")


def decode_record(raw, expected_type, expected_id, ring):
    """Nota o carpeta descifrada (dict) o None si es una lápida."""
    obj = json.loads(raw)
    legacy_key = ring.get(LEGACY_KID) or next(iter(ring.values()))
    if "format" not in obj:  # v1
        if obj.get("id") != expected_id:
            raise DecryptError("el id no coincide con el nombre del fichero")
        if expected_type == "note":
            return {"title": gcm_decrypt_text(obj["title"], legacy_key), "content": gcm_decrypt_text(obj["content"], legacy_key),
                    "folderId": obj.get("folderId"), "isPinned": obj.get("isPinned", False), "color": obj.get("color", "DEFAULT"),
                    "updatedAt": obj.get("updatedAt", 0), "isTrashed": obj.get("isDeleted", False)}
        return {"name": gcm_decrypt_text(obj["name"], legacy_key), "parentId": obj.get("parentId"), "color": obj.get("color", "DEFAULT")}
    if obj.get("format") != "misnotas" or obj.get("type") != expected_type or obj.get("id") != expected_id:
        raise DecryptError("fichero no válido")
    if obj.get("version", 0) >= 3:
        key = ring.get(obj.get("kid"))
        if key is None:
            raise DecryptError("clave %s desconocida" % obj.get("kid"))
    else:
        if obj.get("deleted"):
            return None
        key = legacy_key
    payload = json.loads(gcm_decrypt_text(obj["payload"], key, "%s:%s" % (expected_type, expected_id)))
    if payload.get("deleted"):
        return None
    payload.pop("pad", None)
    return payload


def decrypt_attachment(blob, att_id, ring):
    for key in ring.values():
        try:
            return gcm_decrypt(blob, key, "attachment:" + att_id)
        except DecryptError:
            continue
    raise DecryptError("no se pudo descifrar el adjunto " + att_id)


# ----------------------------------------------------------------------
# Origen de los ficheros
# ----------------------------------------------------------------------

class DirSource:
    def __init__(self, path):
        self.path = path

    def names(self):
        return sorted(os.listdir(self.path))

    def read(self, name):
        with open(os.path.join(self.path, name), "rb") as f:
            return f.read()


class WebDavSource:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/") + "/"
        token = base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
        self.headers = {"Authorization": "Basic " + token, "User-Agent": "misnotas-decrypt"}

    def _request(self, url, method="GET", body=None, extra=None):
        headers = dict(self.headers)
        headers.update(extra or {})
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()

    def names(self):
        body = b'<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>'
        xml = self._request(self.url, "PROPFIND", body, {"Depth": "1", "Content-Type": "application/xml"})
        out = []
        for resp in ET.fromstring(xml).iter("{DAV:}response"):
            href = resp.findtext("{DAV:}href") or ""
            if resp.find(".//{DAV:}collection") is not None:
                continue
            name = urllib.parse.unquote(href.rstrip("/").split("/")[-1])
            if name:
                out.append(name)
        return sorted(out)

    def read(self, name):
        return self._request(self.url + urllib.parse.quote(name))


# ----------------------------------------------------------------------
# Exportación
# ----------------------------------------------------------------------

def safe_name(name, fallback):
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip().strip(".")
    return (cleaned or fallback)[:80]


def unique(base, ext, taken):
    candidate = base + ext
    n = 2
    while candidate.lower() in taken:
        candidate = "%s (%d)%s" % (base, n, ext)
        n += 1
    taken.add(candidate.lower())
    return candidate


def front_matter(title, pinned, color, updated):
    lines = ["---", "title: " + json.dumps(title, ensure_ascii=False)]
    if pinned:
        lines.append("pinned: true")
    if color and color != "DEFAULT":
        lines.append("color: " + color)
    if updated:
        lines.append("updated: " + datetime.fromtimestamp(updated / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    lines.append("---")
    return "\n".join(lines) + "\n\n"


def sniff_ext(data):
    if data.startswith(b"\x89PNG"):
        return ".png"
    if data.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if data.startswith(b"GIF8"):
        return ".gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return ".webp"
    if data.startswith(b"%PDF"):
        return ".pdf"
    return ".bin"


ATT_REF = re.compile(r"attachment://([A-Za-z0-9_-]+)")
FILE_LINK = re.compile(r"(?<!!)\[📎 ([^\]]*)\]\(attachment://([A-Za-z0-9_-]+)\)")


def export(source, out_dir, ring, include_trash, log):
    names = source.names()
    folders, notes, warnings = {}, {}, 0
    for name in names:
        for prefix, kind, store in (("folder_", "folder", folders), ("note_", "note", notes)):
            if name.startswith(prefix) and name.endswith(".json"):
                rid = name[len(prefix):-5]
                try:
                    rec = decode_record(source.read(name).decode("utf-8"), kind, rid, ring)
                    if rec is not None:
                        store[rid] = rec
                except (DecryptError, ValueError, KeyError) as e:
                    warnings += 1
                    log("aviso: %s: %s" % (name, e))

    def folder_path(fid):
        parts, seen = [], set()
        while fid and fid in folders and fid not in seen:
            seen.add(fid)
            parts.insert(0, safe_name(folders[fid].get("name", ""), "carpeta"))
            fid = folders[fid].get("parentId")
        return parts

    os.makedirs(out_dir, exist_ok=True)
    att_dir = os.path.join(out_dir, "attachments")
    exported_atts = {}
    taken = {}
    count = 0
    for rid, note in sorted(notes.items(), key=lambda kv: kv[1].get("title", "")):
        trashed = note.get("isTrashed", False)
        if trashed and not include_trash:
            continue
        parts = ([".trash"] if trashed else []) + folder_path(note.get("folderId"))
        directory = os.path.join(out_dir, *parts)
        os.makedirs(directory, exist_ok=True)
        content = note.get("content", "")
        file_names = {m.group(2): m.group(1) for m in FILE_LINK.finditer(content)}
        for att_id in sorted(set(ATT_REF.findall(content))):
            if att_id not in exported_atts:
                fname = "att_%s.bin" % att_id
                if fname not in names:
                    continue
                try:
                    data = decrypt_attachment(source.read(fname), att_id, ring)
                except DecryptError as e:
                    warnings += 1
                    log("aviso: %s" % e)
                    continue
                ext = os.path.splitext(file_names.get(att_id, ""))[1] or sniff_ext(data)
                os.makedirs(att_dir, exist_ok=True)
                with open(os.path.join(att_dir, att_id + ext), "wb") as f:
                    f.write(data)
                exported_atts[att_id] = att_id + ext
            rel = "../" * len(parts) + "attachments/" + exported_atts[att_id]
            content = content.replace("attachment://" + att_id, rel)
        title = note.get("title", "")
        base = safe_name(title or (content.strip().splitlines() or ["nota"])[0][:60], "nota")
        fname = unique(base, ".md", taken.setdefault(directory, set()))
        with open(os.path.join(directory, fname), "w", encoding="utf-8") as f:
            f.write(front_matter(title, note.get("isPinned"), note.get("color"), note.get("updatedAt")))
            f.write(content.rstrip("\n") + "\n")
        count += 1
    return count, len(exported_atts), warnings


def main(argv=None):
    p = argparse.ArgumentParser(description="Descifra la carpeta de Mis Notas y la exporta a Markdown.")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--dir", help="carpeta de Mis Notas ya descargada")
    src.add_argument("--url", help="URL WebDAV de la carpeta de Mis Notas")
    p.add_argument("--user", help="usuario del servidor (con --url)")
    p.add_argument("--out", required=True, help="carpeta de salida")
    p.add_argument("--recovery-key", action="store_true", help="usar la clave de recuperación (bóveda v3) en vez de la contraseña")
    p.add_argument("--include-trash", action="store_true", help="exportar también la papelera (en .trash/)")
    args = p.parse_args(argv)

    if args.url:
        if not args.user:
            p.error("--url necesita --user")
        dav_pw = os.environ.get("MISNOTAS_DAV_PASSWORD") or getpass.getpass("Contraseña del servidor: ")
        source = WebDavSource(args.url, args.user, dav_pw)
    else:
        source = DirSource(os.path.expanduser(args.dir))

    try:
        keyinfo = source.read("keyinfo.json").decode("utf-8")
    except Exception as e:
        sys.exit("No se encuentra keyinfo.json en la carpeta: %s" % e)

    try:
        if args.recovery_key:
            ring, _ = open_vault(keyinfo, recovery=os.environ.get("MISNOTAS_RECOVERY_KEY") or getpass.getpass("Clave de recuperación: "))
        else:
            ring, _ = open_vault(keyinfo, password=os.environ.get("MISNOTAS_PASSWORD") or getpass.getpass("Contraseña maestra: "))
    except DecryptError as e:
        sys.exit("Error: %s" % e)

    notes, atts, warnings = export(source, os.path.expanduser(args.out), ring, args.include_trash, lambda m: print(m, file=sys.stderr))
    print("Exportadas %d notas y %d adjuntos a %s%s" % (notes, atts, args.out, " (%d avisos)" % warnings if warnings else ""))
    return 0 if warnings == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
