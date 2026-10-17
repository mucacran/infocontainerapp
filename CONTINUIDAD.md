# Continuidad de InfoContainer — 17 de octubre de 2026

Proyecto activo: `C:/proyectosMucacran/infocontainerapp`, Mucacrán S.A.S. Application ID: `com.mucacran.infocontainer`. Kotlin, Compose Material 3, SQLite v2 y fotografías privadas. No cambiar ID ni desinstalar/borrar datos para actualizar.

Hosting de referencia: `https://loinde.com.ec`, InfoContainer 1.3.1. Usuario de pruebas: `infocontainer_android`, rol Operador de InfoContainer. Sergio realiza las acciones físicas y las actualizaciones alojadas; no hay acceso a archivos del hosting desde este proyecto.

## Estado de esta etapa

Implementada la primera lectura autenticada con pantalla **Conexión**, accesible desde Inicio y desde el formulario. Regresar conserva el ViewModel del formulario, contenido, referencia, identidad y sesión de fotografías sin llamar de nuevo a `open`. No se tocaron repositorios ni esquema SQLite ni almacenamiento/recuperación de fotografías.

`network/ConnectionProbe.kt` contiene origen no secreto, ruta única y resultados sin datos sensibles. `network/WordPressConnectionProbe.kt` hace únicamente GET de lista con `per_page=1&_fields=id`; Basic Auth, HTTPS Android normal, sin redirecciones, reintentos, autenticadores automáticos ni caché en disco. OkHttp 4.12.0 controla el transporte; Gson 2.11.0 valida estrictamente una lista JSON completa, incluyendo `[]`, con límite de 64 KiB. Timeouts: 10 segundos por operación y 20 segundos total. No llamadas al cargar UI.

`ui/ConnectionState.kt` es un ViewModel sólo en memoria, sin SavedStateHandle. La contraseña se oculta; cerrar cancela y limpia usuario/contraseña/resultado. Girar conserva la solicitud existente y no inicia otra. Muerte del proceso exige reintroducir el secreto. Nunca solicitar la contraseña en el chat, guardarla en archivos/SQLite/preferencias, incorporarla a comandos, logs, código o APK, ni añadir autoridades Kaspersky/excepciones de confianza.

## Verificación

`assembleDebug` y `assembleDebugAndroidTest` correctos con JBR 21; Lint: **0 errores y 16 advertencias**. **30 unitarias pertinentes aprobadas** (13 transporte, 14 formulario, 3 igualdad), sin fallos/errores/omitidas. Incluyen lista vacía válida, 24 respuestas inválidas, 401/403, redirecciones de mismo/otro origen/HTTP sin seguimiento, reintentos bloqueados, timeout, cancelación y errores TLS. El parser consume todos los tokens estándar de Gson, sin `skipValue`, y limita profundidad a 128.

**3 instrumentadas focalizadas aprobadas en `ANNXVB5708002966`**, 0 fallos: ida/vuelta desde formulario nuevo y existente conservando contenido/referencia/ID/sesión sin guardar, y contraseña oculta + doble pulsación + dos giros reales manteniendo una única comprobación falsa + cancelación/limpieza al salir. Las pruebas HTTP usan únicamente un servidor HTTPS simulado local y entradas sintéticas. Las pruebas de navegación usan la UI real con repositorio sólo en memoria y un transporte falso sin sockets; no abren la BD de producción ni cámara/galería. No se repitieron pruebas anteriores de fotos, cámara o recuperación.

Ambos APK instalados incrementalmente con `adb install -r -t`, sin desinstalar/borrar datos; app principal abierta al terminar. BD `infocontainer.db` y los 11 archivos de fotografías privadas tienen las mismas huellas SHA-256 antes y después de actualización/pruebas, sin visualizar originales. Evidencia y logs locales excluidos de Git: `app/build/reports/connection/`, `app/build/reports/connection-network-verification-summary.json`, XML JVM e informes Lint.

**Contador de esta tarea:** REST hacia WordPress alojado **0**; OpenAI **0**; HTTP simulado de loopback **74 total**, **40 en suite final**. Los 34 anteriores corresponden a la suite que detectó contenido de cadena omitido al usar `skipValue`; corregido con lectura completa de tokens. Dos intentos iniciales no llegaron a HTTP por hostname Windows del fixture; corregido sólo allí. La confianza TLS Android y Kaspersky no se modificaron. Los resultados HTTP de loopback son simulados; la navegación/rotación instrumentadas sí se ejecutaron en el teléfono.

## Archivos de esta etapa

| Archivos | Cambio |
| --- | --- |
| `app/src/main/java/com/mucacran/infocontainer/network/ConnectionProbe.kt`, `WordPressConnectionProbe.kt` | Configuración pública, contrato y transporte de lectura |
| `app/src/main/java/com/mucacran/infocontainer/ui/ConnectionState.kt`, `ConnectionScreen.kt` | Sesión sólo en memoria, formulario de conexión y estados |
| `app/src/main/java/com/mucacran/infocontainer/MainActivity.kt` | Navegación de ida y vuelta, ViewModel de conexión |
| `app/src/main/java/com/mucacran/infocontainer/ui/HomeScreen.kt`, `InspectionFormScreen.kt` | Accesos a Conexión |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml` | Permiso Internet, HTTP bloqueado y textos |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | OkHttp/Gson y dependencias exclusivas de pruebas |
| `app/src/test/java/com/mucacran/infocontainer/network/WordPressConnectionProbeTest.kt` | HTTPS local simulado, JSON y errores |
| `app/src/androidTest/java/com/mucacran/infocontainer/ui/ConnectionNavigationTest.kt` | Navegación real, preservación y ciclo de vida |
| `app/src/debug/java/com/mucacran/infocontainer/ui/ConnectionNavigationTestActivity.kt`, `app/src/debug/AndroidManifest.xml` | Arnés aislado no exportado, sólo debug |
| `README.md`, `CONTINUIDAD.md` | Uso físico, límites, verificación y continuidad |

## Pendiente físico

Sergio debe abrir Conexión en el teléfono, introducir `infocontainer_android` y su contraseña de aplicación allí, pulsar Probar conexión y comunicar sólo el estado final. No conocemos ni pedimos el secreto. Los resultados PowerShell previos (401 anónimo, 200 `[]` autenticado con Kaspersky temporalmente pausado) son evidencia del hosting, no del cliente Android. No declarar autenticación Android superada sin resultado real observado. Las instrucciones detalladas están en README.

Sin almacenamiento persistente de credenciales, sincronización, escrituras remotas, envío de fotos ni llamadas OpenAI. Sin commits, push ni despliegues web. Instalación debug autorizada sólo incremental, sin desinstalar ni borrar datos.
