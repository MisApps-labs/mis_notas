# misnotas-decrypt

Descifra la carpeta de Mis Notas de tu servidor y la exporta a Markdown, **sin la app**. Así tus notas
no dependen de Mis Notas: con tu contraseña maestra (o la clave de recuperación de una bóveda v3) las
recuperas en cualquier ordenador.

La salida tiene el mismo formato que la copia de seguridad de la app: un `.md` por nota con su cabecera
(título, fijada, color, fecha), una carpeta por cada carpeta y los adjuntos en `attachments/`. Se puede
abrir con Obsidian o cualquier editor, o volver a importar en Mis Notas (comprimida en un ZIP).

## Instalación

Python 3.9 o posterior:

```bash
pip install -r requirements.txt     # cryptography y argon2-cffi
```

## Uso

```bash
# Desde una copia de la carpeta (por ejemplo, sincronizada con el cliente de escritorio de Nextcloud)
python3 misnotas_decrypt.py --dir ~/Nextcloud/MisNotas --out ./mis-notas

# Directamente desde el servidor por WebDAV
python3 misnotas_decrypt.py --url https://nube.ejemplo.com/remote.php/dav/files/ana/MisNotas --user ana --out ./mis-notas

# Con la clave de recuperación (bóvedas v3) en lugar de la contraseña
python3 misnotas_decrypt.py --dir ./MisNotas --out ./mis-notas --recovery-key

# Incluir la papelera (va a .trash/)
python3 misnotas_decrypt.py --dir ./MisNotas --out ./mis-notas --include-trash
```

Las contraseñas se piden por teclado. Para scripts se pueden pasar en `MISNOTAS_PASSWORD`,
`MISNOTAS_RECOVERY_KEY` y `MISNOTAS_DAV_PASSWORD`.

Código de salida: `0` si todo fue bien, `1` si la contraseña es incorrecta o falta `keyinfo.json`, `2` si
algún fichero no se pudo descifrar (se indica cuál y se exporta el resto).

## Qué lee

Los formatos v1, v2 y v3 descritos en [docs/SYNC_FORMAT.md](../../docs/SYNC_FORMAT.md): PBKDF2 o Argon2id,
llavero con varias claves tras una rotación, ficheros con relleno, lápidas (se omiten) y adjuntos.
Solo lee: nunca escribe en el servidor.

## Pruebas

`app/src/test/.../CliFixtureTest.kt` genera bóvedas de ejemplo con el código de la app en
`app/build/cli-fixture/`:

```bash
./gradlew testDebugUnitTest --tests '*CliFixtureTest*'
MISNOTAS_PASSWORD="$(cat app/build/cli-fixture/password.txt)" \
  python3 tools/misnotas-decrypt/misnotas_decrypt.py --dir app/build/cli-fixture/v3 --out /tmp/prueba
```
