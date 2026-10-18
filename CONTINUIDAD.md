# Continuidad de InfoContainer — 18 de octubre de 2026

## Etapa vigente: corrección de informe y revisiones IA simulados

La desaparición se debía a que `AiAnalysisPanel` componía el informe y los botones sólo si `AiDemoState.analysis` existía, mientras `runSimulated()` guardaba la respuesta en `AiAnalysisState.run`. El panel usa ahora esa propuesta y mantiene el editor en el mismo ViewModel. El desplazamiento de Conexión ya era válido; antes no existían controles adicionales que desplazar.

Las revisiones usan el coordinador, conservan el esquema completo de la propuesta editada y sólo confirman tras respuesta. Hay guardia contra pulsaciones simultáneas, `finally` y timeout con error visible. Los botones están en filas de ancho completo. Se conserva el coordinador al recrear la Activity para mantener UUID. La fábrica de simulación sólo permite clases falsas concretas sin HTTP y conserva `AiExecutionGate(false)`; la demostración se inyecta sólo en debug.

**Verificación:** 8 JVM y 5 casos de interfaz distintos aprobados; interfaz real en un viewport 300 × 480 dp, edición/aceptación/rechazo, error/timeout/reintento, desplazamiento completo, recreación e idempotencia y conservación del formulario. Compilación y APK de pruebas correctos; Lint 0 errores/16 advertencias previas. Ambos APK instalados con `-r -t`, sin desinstalar ni borrar datos. BD y 13 originales privados conservan exactamente sus SHA-256. Hostinger y OpenAI: 0 solicitudes. No se ejecutó Git.

Sergio puede abrir Conexión, pulsar Ejecutar flujo simulado, desplazarse al informe, editarlo y registrar la decisión; no necesita credenciales. Se espera «SIMULADO · Revisión de edición confirmada.». Detalles, archivos, intentos iniciales del arnés y evidencia en [docs/CORRECCION_INFORME_IA.md](docs/CORRECCION_INFORME_IA.md) y `app/build/reports/ai-analysis-regression/`. La IA real sigue bloqueada y no se declara comprobada.

## Etapa anterior: fotografías autenticadas con WordPress 1.3.5

El contexto actual informa plugin 1.3.5 en `https://loinde.com.ec` y autenticación de lectura ya comprobada físicamente por Sergio. Esta tarea sólo realizó desarrollo local y simulaciones: **0 peticiones al hosting y 0 a OpenAI**. No se modificó WordPress/Hostinger ni se hicieron commits, push, pull o despliegues.

### Implementación preparada

- **Conexión → Preparar prueba de fotografía**: elegir un borrador ya guardado, una miniatura, categoría y un ID de inspección remota existente, aislada y `draft`. Se confirma explícitamente el destino. No se exige identificador de contenedor al borrador local ni se crean contenedores/inspecciones automáticamente. La ruta de lectura de estado no está en el contrato facilitado; el servidor aplica la editabilidad al POST.
- Credenciales sólo en `ConnectionState`, en memoria mientras permanece Conexión. Salir cancela transferencia y limpia credenciales. El panel usa esa sesión y no incorpora almacenamiento de secretos. HTTPS normal de Android, origen fijo, sin redirecciones ni reintentos automáticos.
- `PhotoUploadSource` comprueba originales privados por firma, decodificación reducida, límites REST y SHA-256 en IO. No modifica ni comprime el original. Los límites locales anteriores se mantienen; una imagen demasiado grande para REST se rechaza sin alterarla.
- `PhotoTransferStore` usa AtomicFile y mutex en `noBackupFilesDir/photo_transfers/{draft_UUID}.json`. Guarda únicamente metadatos no secretos. SQLite permanece v2 sin migraciones. `prepare` durable antes de red fija borrador→inspección→fotoUUID→payload; el UUID local es el `client_photo_id`. Timeout/cancelación conserva el plan para un reintento manual idempotente. No editar/borrar el diario ni cambiar UUID/destino para resolver un resultado incierto.
- `WordPressPhotoApi`: POST multipart único `file`/`client_photo_id`/`category`, descripción opcional y soporte de captured_at opcional (UI lo omite si no existe fecha fiable). Comprueba respuestas 201/200 frente a todos los campos esperados y distingue conflictos 409. No usa cuerpos/mensajes arbitrarios del servidor en la UI o logs.
- GET de lista/metadatos y recuperación autenticada de bytes por streaming. Verifica tamaño/hash y la asociación local cuando existe. Descarta el stream sin sustituir imágenes ni publicar URLs; no incorpora descargas al formulario.
- Ningún envío al abrir/restaurar/recomponer. El ViewModel bloquea pulsaciones simultáneas y conserva trabajo en cambios de configuración. Cerrar cancela; reabrir/reiniciar exige reintroducir credenciales y seleccionar el mismo borrador para recuperar la intención, sin autoenvío. Una selección de borrador fallida desactiva el destino anterior.

### Verificación final

Compilación debug y APK instrumentado correctos; Lint **0 errores/16 advertencias previas**. **30 unitarias nuevas aprobadas** (20 HTTP simulado, 10 coordinador). **21 instrumentadas distintas aprobadas** en `ANNXVB5708002966`: 7 validación/límites, 10 diario real, 4 sesión con API sin sockets. 24 ejecuciones instrumentadas en total, sin fallos (20 iniciales y 4 de sesión tras ajuste de selección). No se repitieron suites históricas de cámara, fotos del formulario o recuperación ni se afirma rotación física nueva.

**66 solicitudes HTTPS simuladas de loopback**, cero hosting/OpenAI. Se cubren multipart, UUID estable, 201/200, timeout→reintento, 409, 401/403/413/415, TLS, redirects/retries bloqueados, corrupción/conflictos, doble pulsación, cierre/limpieza y comprobación de integridad/asociación.

APK final debug y de pruebas instalados con `-r -t`, sin desinstalar ni borrar datos. La app quedó abierta. Huellas de `infocontainer.db` y los **11 originales privados idénticas antes y después**; no se visualizaron imágenes existentes. Evidencias bajo `app/build/reports/photo-transfer/` e informes Lint/XML JVM habituales; resumen final `verification-summary.json`.

### Archivos de esta etapa

| Archivos | Responsabilidad |
| --- | --- |
| `app/src/main/java/com/mucacran/infocontainer/network/PhotoApi.kt`, `WordPressPhotoApi.kt` | Modelos, errores, transporte y verificación de respuesta/bytes |
| `app/src/main/java/com/mucacran/infocontainer/data/PhotoUploadSource.kt`, `PhotoTransferCoordinator.kt` | Validación del original y orden durable antes del envío |
| `app/src/main/java/com/mucacran/infocontainer/data/transfer/PhotoTransferStore.kt` | Diario privado de relaciones e idempotencia |
| `app/src/main/java/com/mucacran/infocontainer/data/LocalPhotoStore.kt` | Acceso interno de lectura al original, sin cambiar operaciones existentes |
| `app/src/main/java/com/mucacran/infocontainer/ui/PhotoTransferState.kt`, `PhotoTransferPanel.kt` | Sesión de prueba y controles de envío/consulta/recuperación |
| `app/src/main/java/com/mucacran/infocontainer/ui/ConnectionScreen.kt`, `MainActivity.kt` | Integración y cierre/cancelación junto a credenciales |
| `app/src/main/java/com/mucacran/infocontainer/ui/PhotographsSection.kt`, `app/src/main/res/values/strings.xml` | Reutilización interna de miniaturas y aviso de sesión |
| `app/src/test/java/com/mucacran/infocontainer/network/WordPressPhotoApiTest.kt`, `data/PhotoTransferCoordinatorTest.kt` | 30 unitarias con HTTPS simulado y dobles |
| `app/src/androidTest/java/com/mucacran/infocontainer/data/PhotoUploadSourceTest.kt`, `data/transfer/PhotoTransferStoreTest.kt`, `ui/PhotoTransferSessionTest.kt` | 21 instrumentadas nuevas aisladas |
| `.gitignore`, `tools/New-PhotoTransferFixture.ps1` | Excluir diarios privados y crear imagen sintética local |
| `README.md`, `CONTINUIDAD.md`, `docs/PRUEBA_FOTOGRAFIAS_WORDPRESS.md` | Evidencia, límites y procedimiento físico |

### Próxima acción física, aún pendiente

Solicitar autorización para la prueba real y obtener un ID de inspección de prueba aislada `draft` del responsable WordPress. Copiar al teléfono la imagen sintética `app/build/reports/photo-transfer/infocontainer-prueba.png`, guardarla en un borrador nuevo e incompleto, abrir Conexión e introducir la contraseña de aplicación allí. Elegir borrador/foto/ID/categoría, confirmar el entorno de prueba, subir, reintentar mismo UUID, consultar y verificar bytes. Procedimiento exacto y cantidades esperadas en `docs/PRUEBA_FOTOGRAFIAS_WORDPRESS.md`.

No declarar comprobada la integración real hasta observar esta prueba autorizada. Limitaciones deliberadas: ID remoto preparado fuera de la app, sin persistencia de credenciales, sin conversión de originales grandes, sin importación de descargas y sin creación automática de registros. Los límites PHP, permisos e ID remoto reales siguen por comprobar.

## Historial: etapa anterior de primera lectura autenticada

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
## Estado actual — creación sintética Android preparada

Se añadió en debug la creación explícita de un contenedor sintético y una inspección `draft` desde la pantalla de prueba de fotografías. La app valida el identificador ISO 6346, usa los POST `containers` y `containers/{id}/inspections`, exige confirmación visible, conserva sólo la asociación no secreta en `noBackupFilesDir` y rellena el ID remoto. No crea nada automáticamente al abrir/recomponer ni sube fotografías. La prueba local simuló ambos POST; Hostinger recibió 0 solicitudes durante desarrollo y OpenAI 0.

El debug actualizado se instaló incrementalmente con `adb install -r -t`, sin desinstalar ni borrar datos. Para una operación incierta no se repite el POST automáticamente; Sergio debe revisar el estado remoto antes de intentar otra creación. La prueba física y la subida de fotografía siguen siendo acciones separadas.

