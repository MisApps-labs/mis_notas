<p align="center">
  <img src="capturas/Grafico%20de%20funciones.jpeg" alt="Mis Notas" width="100%">
</p>

<h1 align="center">Mis Notas</h1>

<p align="center">
  Notas en Markdown para Android, con carpetas, cifrado de extremo a extremo y sincronización con tu propio Nextcloud.
</p>

<p align="center">
  <a href="LICENSE"><img alt="Licencia: GPL v3" src="https://img.shields.io/badge/licencia-GPLv3-blue.svg"></a>
  <a href="https://github.com/MisApps-labs/mis_notas/actions/workflows/android.yml"><img alt="CI" src="https://github.com/MisApps-labs/mis_notas/actions/workflows/android.yml/badge.svg"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg">
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.ferlagod.misnotas&amp;hl=es"><img alt="Disponible en Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/es_badge_web_generic.png" height="80"></a>
</p>

---

Mis Notas combina un diseño limpio con la organización por carpetas y el Markdown de apps como Joplin o Notesnook. Tus notas viven en el teléfono, y si quieres se sincronizan **cifradas** con tu servidor Nextcloud: el servidor solo guarda datos ilegibles.

## Otras plataformas

Además de esta aplicación para Android, puedes acceder a tus notas sincronizadas en otros dispositivos utilizando:

- 🖥️ **[App para escritorio](https://github.com/MisApps-labs/mis_notas_desktop)** (Windows, macOS, Linux).
- 🌐 **[Extensión para el navegador](https://github.com/MisApps-labs/mis_notas_extensiones)** (Chromium y Firefox).

## Capturas

<table align="center">
  <tr>
    <td align="center"><img src="capturas/Principal.png" alt="Pantalla principal" width="220"><br><sub><b>Inicio</b>: vistas rápidas y carpetas</sub></td>
    <td align="center"><img src="capturas/Todas%20las%20notas.png" alt="Todas las notas" width="220"><br><sub><b>Notas</b>: cuadrícula, búsqueda y progreso de tareas</sub></td>
    <td align="center"><img src="capturas/Nota.png" alt="Nota abierta" width="220"><br><sub><b>Nota</b>: Markdown con listas y tareas</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="capturas/Ajustes1.png" alt="Ajustes de apariencia y privacidad" width="220"><br><sub><b>Ajustes</b>: tema, AMOLED, bloqueo y papelera</sub></td>
    <td align="center"><img src="capturas/Ajustes2.png" alt="Sincronización con Nextcloud" width="220"><br><sub><b>Sincronización</b>: Nextcloud con cifrado E2EE</sub></td>
    <td></td>
  </tr>
</table>

## Características

- **Local primero**: funciona sin conexión y sin cuenta. La sincronización es opcional.
- **Carpetas anidadas** con colores, notas fijadas y papelera con restauración (y vaciado automático a los 30 días, desactivable).
- **Markdown**: títulos, negrita, cursiva, tachado, código, bloques de código, citas, listas, listas numeradas, tablas, enlaces (también direcciones sueltas), separadores y **listas de tareas** que se marcan con un toque.
- **Editor cómodo**: deshacer/rehacer, las listas continúan solas al pulsar Intro, barra de formato que actúa sobre la línea o la selección, contador de palabras y tiempo de lectura.
- **Imágenes** adjuntas (varias a la vez; se comprimen a WebP y se sincronizan como ficheros cifrados aparte) con visor a pantalla completa y zoom, y **cualquier otro archivo** (PDF, audio, documentos…, hasta 25 MB) que se abre con la app que elijas.
- **Selección múltiple** con pulsación larga: fijar, cambiar color, mover de carpeta o borrar varias notas a la vez, y **"Deshacer"** al mandar algo a la papelera.
- **Enlaces entre notas** con `[[Título]]` o `[[Título|texto]]` (como en Obsidian): se abren con un toque, crean la nota si no existe y cada nota muestra qué notas la enlazan.
- **Plantillas**: elige una carpeta de plantillas en Ajustes y úsalas desde el menú de la nota; `{{fecha}}` y `{{hora}}` se rellenan solos.
- **Etiquetas** con `#hashtags` en el propio texto (`#trabajo`, `#viaje/2026` con subetiquetas), como en Obsidian o Bear: aparecen en el inicio con su recuento, en cada tarjeta y resaltadas en la nota.
- **Vista de cuadrícula o lista**, orden por fecha o título, progreso de las listas de tareas en cada tarjeta y **búsqueda instantánea** que ignora acentos, resalta los resultados y admite operadores: `"frase exacta"`, `-excluir` y `#etiqueta`.
- **Widget** con botón de nota nueva y tus notas fijadas y recientes, **mosaico de ajustes rápidos** «Nueva nota» y **recordatorios** por nota (con notificación que no enseña el contenido si la app está bloqueada).
- **Compartir**: envía notas a otras apps, cópialas o duplícalas, y crea notas desde "Compartir" en cualquier app (texto o imágenes). Acceso directo "Nueva nota" manteniendo pulsado el icono.
- **Copia de seguridad en Markdown**: exporta todo a un ZIP (carpetas, adjuntos y metadatos) e importa `.zip`, `.md` y `.txt`. Compatible con Obsidian, Joplin, etc.
- **Importar desde otras apps**: Google Keep (ZIP de Google Takeout), Joplin (`.jex`), Evernote (`.enex`), Simplenote y Standard Notes (copia sin cifrar). Se conservan carpetas o cuadernos, listas de tareas, adjuntos, fijadas, papelera y fechas; las etiquetas pasan a `#hashtags`.
- **Bloqueo de la app** con huella, cara o PIN del dispositivo, y contenido oculto en las apps recientes.
- **Móviles plegables y tablets**: en pantallas anchas la lista y la nota se ven lado a lado; al plegar el móvil la nota sigue abierta sin perder nada.
- **Sincronización con Nextcloud, ownCloud o cualquier WebDAV** automática: al abrir la app, al editar y cada ~30 minutos en segundo plano. Inicio de sesión de Nextcloud en el navegador, certificados autofirmados y prueba de conexión con diagnóstico.
- **Cifrado de extremo a extremo** AES-256-GCM con una contraseña maestra que nunca sale del dispositivo.
- **Sin conflictos silenciosos**: si una nota se edita a la vez en dos dispositivos y los cambios están en líneas distintas, se **fusionan solos**; si chocan en las mismas líneas, se conservan ambas versiones.
- **Historial de versiones** de cada nota (en el dispositivo): se guarda al editar, antes de que la sincronización sustituya o fusione el texto y antes de restaurar, y se restaura con un toque.
- Diseño de la familia **Mis Apps** (compartido con Mis Podcast): tipografías Outfit e Inter, verde del logo como color principal, tema claro, oscuro o del sistema con opción de negro puro AMOLED, e icono temático en Android 13+.
- **Disponible en español, inglés, francés, alemán e italiano**: se elige en Ajustes (o "Idioma del sistema"). En Android 13+ también desde los ajustes de idioma del sistema.
- Sin anuncios, sin rastreadores, sin servicios de Google.

## Instalación

<a href="https://play.google.com/store/apps/details?id=com.ferlagod.misnotas&amp;hl=es"><img alt="Disponible en Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/es_badge_web_generic.png" height="60"></a>

Instálala desde [Google Play](https://play.google.com/store/apps/details?id=com.ferlagod.misnotas&hl=es) o, si lo prefieres, a mano:

1. Descarga el último `.apk` desde la página de [Releases](https://github.com/MisApps-labs/mis_notas/releases).
2. Ábrelo en tu teléfono y permite la instalación desde esa fuente si Android te lo pide.

Requisitos: Android 8.0 (API 26) o superior.

## Conectar con tu servidor

Funciona con **Nextcloud, ownCloud y cualquier servidor WebDAV** (Synology, Seafile, Koofr, rclone, Apache…).

1. Abre **Ajustes** (icono de engranaje) → **Sincronización**.
2. Elige el tipo de servidor:
   - **Nextcloud / ownCloud**: escribe la dirección (p. ej. `https://nube.ejemplo.com`) y pulsa **Iniciar sesión en el navegador**. Autorizas la app y Nextcloud le da una contraseña de aplicación propia, sin copiar nada a mano. También puedes escribir una contraseña de aplicación.
   - **Otro WebDAV**: escribe la dirección de la carpeta para tus notas, tu usuario y tu contraseña.
3. Escribe la **contraseña maestra**: la que cifra tus notas.
4. Si quieres, pulsa **Probar conexión**: revisa paso a paso el certificado, las credenciales, los permisos y que el servidor no permita pisar cambios entre dispositivos.
5. Pulsa **Conectar y cifrar**.

¿Certificado autofirmado, CA propia o un NAS en casa por HTTP? Consulta [docs/SERVIDORES.md](docs/SERVIDORES.md).

### Usar varios dispositivos

Instala la app en el otro dispositivo y conéctalo con la **misma cuenta y la misma contraseña maestra**. Si la contraseña no coincide, la app te avisará y no subirá nada.

### Modo sin cifrar (compatible con Nextcloud Notes)

Si prefieres poder leer y editar tus notas desde la web de Nextcloud, la app **Nextcloud Notes**, Obsidian o cualquier editor, al conectar elige **Sin cifrar (.md)**. Cada nota es un fichero `Título.md` en la carpeta `Notes/` (la misma que usa Nextcloud Notes) y cada carpeta de la app es un directorio. Los adjuntos van en `.attachments/`. Los cambios hechos fuera de la app (editar, crear, renombrar o borrar ficheros) se sincronizan con las mismas protecciones: fusión de cambios, papelera y freno ante borrados masivos.

Ten en cuenta que en este modo quien administre el servidor puede leer las notas, y que fijado, color y papelera solo se guardan en el dispositivo. La app de escritorio y la extensión usan siempre el modo cifrado.

### Cifrado v3 (opcional)

En **Ajustes → Sincronización → Cifrado** puedes actualizar la bóveda a **v3**: Argon2id, **clave de recuperación**, **cambio de contraseña maestra**, **rotación de la clave**, ficheros con relleno y borrados cifrados. Lo admiten Mis Notas para Android 1.0.2, la [app de escritorio](https://github.com/MisApps-labs/mis_notas_desktop) 1.1 y la [extensión](https://github.com/MisApps-labs/mis_notas_extensiones) 1.1. Las versiones anteriores dejan de sincronizar con esa cuenta hasta que se actualicen, sin estropear nada.

> [!WARNING]
> **Si olvidas la contraseña maestra y no tienes clave de recuperación, no hay forma de recuperar las notas guardadas en el servidor.** Ni el administrador de Nextcloud ni el autor de la app pueden descifrarlas. Guárdala en un gestor de contraseñas.

## Tus notas no dependen de la app

- El [formato del servidor](docs/SYNC_FORMAT.md) está documentado al completo.
- [`tools/misnotas-decrypt`](tools/misnotas-decrypt) es un programa de línea de comandos (Python) que descifra la carpeta del servidor y la exporta a Markdown con tu contraseña maestra o tu clave de recuperación, sin necesitar Mis Notas.
- La copia de seguridad exporta Markdown normal que abre Obsidian o cualquier editor.

## Seguridad en breve

- La clave se deriva de tu contraseña maestra con PBKDF2-HMAC-SHA256 (600.000 iteraciones) y un *salt* aleatorio; con el cifrado v3, con **Argon2id** y cifrado en sobre.
- Cada nota, carpeta e imagen se cifra con AES-256-GCM antes de salir del teléfono. El título, el contenido, la carpeta, el color y las fechas van todos dentro del bloque cifrado.
- Cada bloque cifrado queda vinculado a su identificador, así que el servidor no puede intercambiar ni modificar ficheros sin que la app lo detecte.
- Las credenciales y la clave se guardan en el dispositivo con `EncryptedSharedPreferences` (Android Keystore).
- Las notas **no** se incluyen en la copia de seguridad en la nube de Google.

Lo que **no** protege: el servidor puede ver cuántas notas tienes, su tamaño aproximado y cuándo cambian. En el teléfono las notas se guardan sin cifrar dentro del almacenamiento privado de la app, que Android protege con el cifrado del propio dispositivo. El botón del candado oculta las notas y pausa la sincronización hasta que vuelvas a escribir la contraseña maestra, pero no las cifra en el teléfono.

Detalles técnicos en [SECURITY.md](SECURITY.md) y en el [formato de sincronización](docs/SYNC_FORMAT.md).

## Cómo funciona la sincronización

- Cada nota y carpeta es un fichero JSON cifrado en `MisNotas/` de tu Nextcloud (`note_<id>.json`, `folder_<id>.json`); las imágenes son `att_<id>.bin`.
- La app usa los ETag de WebDAV para descargar solo lo que ha cambiado y `If-Match` al subir, para no sobrescribir nunca un cambio que no ha visto.
- **Conflictos**: si la misma nota cambió en dos sitios, se fusionan los cambios línea a línea con la última versión común (como `git merge`). Solo si los dos cambiaron las mismas líneas se queda la versión más reciente y la otra se guarda como una nota nueva con el título "… (conflicto)". La versión local anterior queda siempre en el historial.
- **Borrados**: la papelera se sincroniza entre dispositivos. Al vaciarla, la app deja en el servidor una "lápida" para que el resto de dispositivos también la borren.
- Al eliminar una carpeta, sus notas pasan a "Todas las notas" y sus subcarpetas suben un nivel.
- **Freno ante borrados masivos**: si en una sincronización el servidor quiere borrar o mandar a la papelera de golpe muchas notas (5 o más y al menos una cuarta parte de las que tienes, o 50 o más), la app no lo aplica: conserva las notas, las vuelve a subir y lo indica en Ajustes. Suele pasar si la carpeta del servidor se ha vaciado o es otra.
- **Copia antes de conectar**: al conectar una cuenta, la app guarda primero un ZIP con todas tus notas dentro de su almacenamiento privado, donde otras apps no pueden leerlo (conserva las 3 últimas). Desde Ajustes → Copia de seguridad → «Guardar copia…» puedes sacarlo a la carpeta que elijas e importarlo cuando quieras.

## Compilar desde el código

Necesitas JDK 21 y el Android SDK (Android Studio lo instala todo).

```bash
git clone https://github.com/MisApps-labs/mis_notas.git
cd mis_notas
./gradlew assembleDebug          # APK de depuración en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # tests unitarios
./gradlew lintDebug              # análisis estático (lo ejecuta también el CI)
```

### Firmar una versión release

1. Crea un almacén de claves (una sola vez y **guárdalo bien**: sin él no podrás publicar actualizaciones):
   ```bash
   keytool -genkeypair -v -keystore misnotas-release.jks -alias misnotas -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Copia `keystore.properties.example` a `keystore.properties` y rellénalo. Ese fichero está en `.gitignore`.
3. Ejecuta `./gradlew assembleRelease`. El APK queda en `app/build/outputs/apk/release/`.

Para firmar en GitHub Actions, consulta [CONTRIBUTING.md](CONTRIBUTING.md#publicar-una-versión).

## Estructura del proyecto

```
app/src/main/java/com/ferlagod/misnotas/
├── data/
│   ├── attachment/   Almacén local de imágenes adjuntas
│   ├── backup/       Exportar/importar notas en Markdown (ZIP)
│   ├── local/        Room: entidades, DAOs, migraciones, árbol de carpetas, preferencias
│   ├── remote/       Cliente WebDAV (OkHttp)
│   └── sync/         Bóveda, formato remoto, reglas de fusión, sincronización y WorkManager
├── security/         Cifrado (PBKDF2 + AES-GCM)
├── ui/               Pantallas Jetpack Compose, componentes, tema, lector y editor Markdown
└── util/             Imágenes, idioma, búsqueda y bloqueo de la app

app/src/main/res/values*/strings.xml   Textos (inglés por defecto; es, fr, de, it)
```

## Limitaciones conocidas

- Cambiar la contraseña maestra, rotar la clave y la clave de recuperación necesitan el cifrado v3.
- Los adjuntos tienen un máximo de 25 MB cada uno.

¿Ideas o fallos? Abre una [incidencia](https://github.com/MisApps-labs/mis_notas/issues).

## Contribuir

Las contribuciones son bienvenidas. Lee [CONTRIBUTING.md](CONTRIBUTING.md) antes de abrir un *pull request*. Para vulnerabilidades, sigue [SECURITY.md](SECURITY.md).

## Créditos

- Tipografías [Outfit](https://github.com/Outfitio/Outfit-Fonts) (© 2021 The Outfit Project Authors) e [Inter](https://github.com/rsms/inter) (© 2016 The Inter Project Authors), bajo la [SIL Open Font License 1.1](https://openfontlicense.org). La licencia va incluida en los propios ficheros de fuente.

## Licencia

Copyright © 2026 ferlagod

Este programa es software libre: puedes redistribuirlo y/o modificarlo bajo los términos de la **Licencia Pública General de GNU** publicada por la Free Software Foundation, ya sea la versión 3 de la Licencia o (a tu elección) cualquier versión posterior.

Se distribuye con la esperanza de que sea útil, pero **SIN NINGUNA GARANTÍA**; ni siquiera la garantía implícita de COMERCIABILIDAD o IDONEIDAD PARA UN PROPÓSITO PARTICULAR. Consulta el fichero [LICENSE](LICENSE) para más detalles.

*Nextcloud es una marca registrada de Nextcloud GmbH. Este proyecto no está afiliado a Nextcloud GmbH, Apple, Google, Joplin ni Notesnook.*
