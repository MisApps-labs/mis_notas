# Servidores compatibles

Mis Notas sincroniza con **cualquier servidor WebDAV**. Todo se cifra en el teléfono antes de
subirlo, así que el servidor solo guarda ficheros ilegibles (ver [SECURITY.md](../SECURITY.md)).

Antes de conectar, usa **Probar conexión**: comprueba la dirección, el certificado, el usuario,
que se puede escribir en la carpeta y que el servidor respeta `If-Match` / `If-None-Match`
(lo que impide que dos dispositivos se pisen cambios). Te dice qué falla y cómo arreglarlo.

## Nextcloud y ownCloud

Elige **Nextcloud / ownCloud**, escribe la dirección (`https://nube.ejemplo.com`) y pulsa
**Iniciar sesión en el navegador**. Entras con tu cuenta (también con 2FA o SSO) y Nextcloud
crea una contraseña de aplicación solo para este dispositivo, que puedes revocar desde
*Ajustes personales → Seguridad → Dispositivos y sesiones*.

Si prefieres, toca **Usar una contraseña de aplicación** y escríbela a mano. Las notas se
guardan en `MisNotas/` dentro de tus archivos. También puedes pegar una URL WebDAV completa de
una subcarpeta (`https://…/remote.php/dav/files/usuario/Notas`).

ownCloud 10 funciona igual con contraseña de aplicación (no tiene inicio de sesión en el navegador).

## Otros servidores WebDAV

Elige **Otro WebDAV** y escribe la dirección de la **carpeta** donde quieres las notas. Usa una
carpeta solo para Mis Notas. Si no existe, la app la crea (la carpeta que la contiene sí debe existir).

| Servidor | Dirección de ejemplo | Notas |
|----------|----------------------|-------|
| ownCloud Infinite Scale (oCIS) | `https://ocis.ejemplo.com/remote.php/webdav/MisNotas` | Usa una contraseña de aplicación. |
| Synology (paquete *WebDAV Server*) | `https://nas.local:5006/home/MisNotas` | Activa HTTPS en el paquete. |
| Seafile (SeafDAV) | `https://seafile.ejemplo.com/seafdav/MiBiblioteca/MisNotas` | Activa SeafDAV en el servidor. |
| Koofr | `https://app.koofr.net/dav/Koofr/MisNotas` | Usa una contraseña de aplicación de Koofr. |
| rclone | `http://192.168.1.10:8080/MisNotas` | `rclone serve webdav remoto: --addr :8080 --user … --pass …` |
| Apache `mod_dav` | `https://servidor/dav/MisNotas` | Con `Dav On` y autenticación básica. |
| Nginx | — | El módulo estándar no implementa `PROPFIND`; necesita `nginx-dav-ext-module`. |

Son configuraciones conocidas, no una lista de pruebas exhaustiva. Si tu servidor da algún aviso en
*Probar conexión*, abre una incidencia con el resultado.

## Certificados propios y red local

- **Certificado autofirmado o CA propia**: *Probar conexión* muestra la huella SHA-256 del certificado
  y te deja confiar en él tras compararla con la de tu servidor. También puedes importar el `.crt`/`.pem`
  de tu CA en *Certificados de confianza*. Solo se usan en esta app. El certificado debe incluir el
  nombre o la IP del servidor en sus nombres alternativos (SAN).
- **HTTP sin cifrar**: solo se acepta para direcciones de la red local (`192.168.x.x`, `10.x.x.x`,
  `100.64.0.0/10` de Tailscale, `localhost`, `*.local`, `*.lan`…). Las notas siguen cifradas de extremo a
  extremo, pero la contraseña del servidor viaja en claro: mejor HTTPS siempre que puedas.

## Modo sin cifrar

Al conectar puedes elegir **Sin cifrar (.md)**: las notas se guardan como ficheros Markdown legibles
(`Notes/Carpeta/Título.md`, adjuntos en `Notes/.attachments/`), compatibles con Nextcloud Notes y con
cualquier editor. La app nunca usa en este modo una carpeta que ya tenga notas cifradas (`keyinfo.json`).
