# Fotografías Android ↔ WordPress 1.3.5

Esta etapa prepara una prueba supervisada. Las verificaciones de transporte son simuladas; no se han realizado solicitudes reales a `https://loinde.com.ec`. La comprobación completa requiere una prueba física posterior autorizada por Sergio. El contexto recibido informa que la autenticación HTTPS de lectura ya fue comprobada físicamente; no se repite aquí.

## Sesión y alcance

La opción **Preparar prueba de fotografía** está dentro de **Conexión**. Usa el usuario y contraseña de aplicación que Sergio introduce allí, sólo en memoria. No guarda credenciales, no copia autoridades de Kaspersky y no cambia la validación HTTPS/hostname de Android. Salir de Conexión cancela las operaciones y limpia las credenciales. Cerrar sólo el panel de fotografías conserva la pantalla Conexión y cancela la transferencia pendiente.

Se seleccionan un borrador **ya guardado**, una de sus miniaturas, una categoría y el ID de una inspección remota de prueba existente. Este flujo no exige añadir un identificador de contenedor al borrador local ni cambia la experiencia principal basada en fotografías. La preparación del contenedor e inspección de prueba se coordina fuera de esta app con el responsable de WordPress. No se implementan llamadas de creación: el contrato proporcionado no garantiza idempotencia para crear contenedores/inspecciones y no se inventa un mecanismo de reintento que pueda duplicarlos.

La inspección remota debe existir, estar aislada y permanecer en estado `draft`. El contrato facilitado no define una ruta de lectura de estado de inspección; Android solicita confirmación explícita del ID de prueba y el servidor aplica el requisito `draft` al POST (409 si no es editable). Consultar fotografías permite comprobar acceso/existencia, pero no demuestra por sí solo el estado `draft`.

## Conservación e idempotencia

- SQLite sigue en v2; no hay migraciones ni cambios en tablas o repositorios existentes. No se modifican campos, asociaciones de fotografías ni archivos originales de borradores para realizar un envío.
- El diario `noBackupFilesDir/photo_transfers/{draft_UUID}.json` guarda únicamente origen, borrador local, inspección remota, UUID de fotografía, categoría, descripción opcional, tamaño/MIME/SHA-256 y confirmación remota. No guarda usuario, contraseña, cabeceras, URLs con secretos ni bytes de imagen.
- `prepare` termina una escritura AtomicFile antes de abrir la solicitud. Un registro corrupto o conflicto local bloquea el envío en lugar de crear otra asociación. Las instancias comparten un mutex y las operaciones de archivo se ejecutan en IO.
- `client_photo_id` es el UUID de la fotografía local. La misma inspección, UUID, categoría, descripción y contenido se conservan al reintentar, cerrar o reiniciar. Un timeout o cancelación puede haber ocurrido después de que WordPress aceptara el archivo: no se considera fracaso definitivo ni se cambia el UUID.
- Una respuesta 201 o 200 sólo confirma la asociación si inspección, UUID, categoría, MIME, tamaño y SHA-256 coinciden. Después se guarda el ID remoto. Un fallo de confirmación local deja el plan preparado para un reintento idempotente.
- El destino y los metadatos preparados quedan bloqueados en esta etapa, incluso ante errores. Verificar el ID antes del primer envío. No borrar el diario, cambiar el UUID ni crear otra inspección para resolver un resultado incierto.
- No hay envíos al abrir, recomponer, restaurar o girar la pantalla. Cada acción debe ser pulsada por el usuario; una operación en curso deshabilita las demás. El ViewModel conserva la operación durante recreaciones de configuración; al terminar el proceso se reintroducen credenciales y se recupera el plan desde el diario, sin autoenvío.

## Archivo y contrato

El original se comprueba por firma, dimensiones, decodificación reducida y SHA-256 en IO. Para enviar debe ser JPEG, PNG o WebP, de hasta 10 MiB, 10.000 píxeles por dimensión y 20 MP. Los límites locales anteriores de 30 MiB/40 MP se mantienen: una imagen válida para guardar localmente puede ser demasiado grande para WordPress. Se informa y conserva el original; esta etapa **no** comprime ni prepara una copia reducida. Los límites PHP podrían ser menores.

La solicitud es `POST /wp-json/infocontainer/v1/inspections/{id}/photos`, multipart, con un único `file`, `client_photo_id`, `category` y `description` si no está vacía. El cliente también admite `captured_at` ISO 8601 con segundos y zona; la UI inicial lo omite porque el modelo local no conserva una fecha de captura fiable. No se usa como sustituto la fecha de inspección.

OkHttp reutiliza el origen público configurado. HTTPS normal, Basic Auth sólo hacia ese origen, sin redirecciones/reintentos automáticos ni autenticadores o caché en disco. Cada solicitud tiene una guardia de un solo intercambio. Conexión: 10 s; lecturas: 20 s; total de GET de metadatos: 20 s; subida y recuperación de bytes: total de 60 s. El cuerpo de subida vuelve a comprobar tamaño/hash al leer el archivo.

Se distinguen 401, 403, 404, 409 no editable, 409 `infocontainer_photo_retry_conflict`, 400, 413, 415, 500/503, TLS, timeout, respuesta inválida y fallos de integridad. No se muestran mensajes arbitrarios del servidor ni excepciones con datos sensibles.

La recuperación usa `GET /inspections/{id}/photos`, `GET /photos/{id}` y `GET /photos/{id}/file`. Los bytes autenticados se cuentan y se hashean mediante streaming y después se descartan: no se añade una fotografía local ni se sustituye ninguna existente. Se cruza UUID, inspección, ID remoto, categoría, MIME, tamaño y hash con el diario cuando existe una asociación local. Sin asociación local, la UI sólo confirma integridad frente a los metadatos remotos. No se publican enlaces ni se abre un navegador para descargar.

## Pasos físicos posteriores para Sergio

**Requieren autorizar primero la prueba real.** No introducir la contraseña en el chat ni usar la contraseña normal de WordPress.

1. Con el responsable del plugin, obtener el **ID numérico de una inspección aislada de prueba en estado `draft`**, preferentemente sin fotografías. Debe pertenecer a un contenedor válido preparado para pruebas; el rol Operador de InfoContainer debe tener `manage_infocontainer`. No usar una inspección de producción. Esta preparación no se ejecutó durante el desarrollo Android.
2. Copiar al teléfono la imagen sintética local `app/build/reports/photo-transfer/infocontainer-prueba.png` (640×400, 7.176 bytes). SHA-256 del archivo entregado: `6e006dae401370870ddf02ec3354afef91adf8d1d23d0d4901325a8afb656f69`. No hace falta usar una fotografía real. El generador `tools/New-PhotoTransferFixture.ps1` crea esta imagen sin leer fotos ni usar servicios externos; si se regenera, conservar el tamaño/hash que entregue para esa copia.
3. Abrir **Nueva inspección**, seleccionar esa imagen y **Guardar borrador en este teléfono**. Puede ser un borrador incompleto sin identificador. Este paso crea únicamente el borrador sintético de prueba; no editar ni descartar otro trabajo pendiente para hacerlo.
4. Abrir **Conexión**, introducir `infocontainer_android` y su contraseña de aplicación físicamente, y pulsar **Preparar prueba de fotografía**. No es necesario repetir Probar conexión.
5. Seleccionar el borrador sintético y su miniatura. Introducir el ID remoto del paso 1, dejar categoría **Otra**, añadir una descripción opcional de prueba y marcar la confirmación de inspección aislada `draft`. Verificar el ID antes de continuar.
6. Pulsar **Consultar fotografías remotas** para comprobar acceso y anotar la cantidad inicial. Luego pulsar **Subir fotografía seleccionada** una vez. Esperar confirmación, anotar ID remoto y UUID visibles, sin compartir credenciales.
7. Pulsar **Reintentar mismo envío (mismo UUID)** una vez. Debe confirmarse la misma fotografía/ID; consultar de nuevo y comprobar que el número aumentó sólo en uno respecto a la cantidad inicial, sin duplicación. Internamente el contrato espera 201 para la primera creación y 200 para el reintento.
8. En esa fotografía de la lista, pulsar **Recuperar bytes y verificar integridad**. Debe informar tamaño y SHA-256 coincidentes y la asociación local. El archivo original y la foto del borrador deben seguir disponibles.
9. Comunicar sólo los mensajes finales, ID de inspección/foto, UUID, conteos y resultado de integridad. No enviar contraseña, cabeceras, logs con secretos ni fotografías privadas. Salir de Conexión limpia la contraseña; los enlaces no secretos quedan guardados.

La secuencia de los pasos 6–8 realiza, si todo responde a la primera, **6 solicitudes reales**: consulta inicial, primer POST, reintento POST, consulta final, metadatos y archivo para verificar. Una consulta o un reintento adicional suma su propia solicitud. Son cantidades esperadas futuras, no solicitudes efectuadas en desarrollo. No hay OpenAI en ninguna fase.

Si hay timeout, volver a intentar el mismo envío cuando se recupere la red; el UUID y destino deben mantenerse. Si se cierra la app, reabrir Conexión, reintroducir las credenciales y volver a seleccionar **el mismo borrador**: aparecerán el destino y UUID preparados. No crear otra inspección ni borrar datos para reintentar. Ante 409 por contenido diferente o un diario en conflicto, detener la prueba y revisar la asociación; no generar otro UUID como solución.

## Verificación local

Compilación debug/APK de pruebas correctos, Lint 0 errores/16 advertencias previas, 30 unitarias nuevas y 21 instrumentadas distintas aprobadas. La evidencia está en README/CONTINUIDAD y `app/build/reports/photo-transfer/`. El APK final se instaló conservando datos; las huellas de la BD y los 11 originales privados permanecieron iguales. Los fixtures JVM usan HTTPS loopback con CA sintética exclusivamente de test; la app mantiene la confianza normal de Android. Los tests Android usan UUID de caché, imágenes sintéticas y repositorios en memoria; no abren la base de producción. No se repitieron las suites históricas de cámara/galería/recuperación ni se presenta la prueba de estado como rotación física nueva.

Las pruebas simuladas acreditan construcción, respuestas y conservación local. No acreditan PHP/hosting real, permisos concretos de la inspección, conectividad ni subida real. La descarga no incorpora fotos remotas a borradores locales, y la creación automática de contenedores/inspecciones queda fuera de esta prueba supervisada.
