# Adaptación del flujo fotográfico Android

Fecha: 18 de octubre de 2026.

Se implementó la parte compatible del formulario Android. El flujo completo de envío,
análisis y guardado definitivo sigue bloqueado por las capacidades del servidor
pendientes de confirmar y por la ausencia de autorización de presupuesto de IA.
El botón aparece deshabilitado; no ejecuta una simulación ni una llamada de pago.

## Diagnóstico previo a los cambios

- Un módulo Android `:app`, Kotlin y Jetpack Compose Material 3.
- `InspectionFormState` conserva campos y operaciones en un ViewModel,
  `SavedStateHandle` y el registro privado de recuperación.
- Los borradores usan SQLite nativo, esquema v2. Ya se podían guardar incompletos;
  el identificador parecía obligatorio por su etiqueta y posición en el formulario.
- `LocalPhotoStore` mantiene originales privados y diarios de importación.
  La cámara utiliza TakePicture/FileProvider y la galería el selector del sistema.
- Límites locales conservados: 12 fotos, 30 MiB por foto, 150 MiB por borrador,
  40 millones de píxeles; JPEG, PNG o WebP.
- Las herramientas REST y las demostraciones de IA están separadas en Conexión.
  El análisis y las revisiones reales conservan `AiExecutionGate(false)`.

Se encontraron cambios previos en el directorio de trabajo. Se conservaron.
Esta tarea no modificó los transportes, las credenciales, los permisos,
el almacenamiento fotográfico, el esquema ni las migraciones.

## Contratos existentes y bloqueo

La siguiente evidencia procede del cliente Android existente, no de una
validación del plugin actualmente alojado. No se encontraron fuentes PHP,
paquetes del plugin ni un contrato completo de servidor en el proyecto.

| Operación implementada en el cliente | Contrato existente | Límite para este flujo |
| --- | --- | --- |
| Identificación | POST `ai/identifications`, foto y `client_request_id` | Una imagen JPEG/PNG de hasta 10 MiB. La herramienta real existente está restringida a una primera prueba sintética supervisada. |
| Consulta de ejecución | GET `ai/runs/{runId}` | Requiere conocer el ID de ejecución. |
| Subida privada | POST `inspections/{id}/photos`, `client_photo_id` | Requiere una inspección remota existente. |
| Análisis | POST `inspections/{id}/ai-analyses`, UUID y `photo_ids` | Requiere inspección y fotos remotas; no acepta directamente el nuevo borrador local sin asociación remota. |
| Revisión | POST `ai/runs/{id}/reviews`, `client_review_id` | Registra aceptación, edición o rechazo; no acredita el guardado definitivo de la inspección. |
| Altas | Utilidad de prueba en `WordPressPhotoApi` | Crea un contenedor con identificador y después su inspección; es una prueba sintética, sin alta normal recuperable por UUID. |

Rutas relativas a la base existente `/wp-json/infocontainer/v1/`.
Fuentes: [WordPressAiApi.kt](../app/src/main/java/com/mucacran/infocontainer/network/WordPressAiApi.kt),
[WordPressPhotoApi.kt](../app/src/main/java/com/mucacran/infocontainer/network/WordPressPhotoApi.kt)
y [MainActivity.kt](../app/src/main/java/com/mucacran/infocontainer/MainActivity.kt).

No hay un contrato confirmado para crear o asociar una inspección remota sin
identificador manual y finalizarla tras revisión humana. Por eso no se conectó
el botón al alta sintética ni se inventaron endpoints.

Además, los UUID de análisis/revisión del coordinador existente solo viven
en memoria. Debe resolverse su recuperación durable y comprobarse la respuesta
asíncrona del análisis antes de activar este flujo. Los documentos anteriores
mencionan contrato 1.3.5 y hosting informado 1.3.6; esa correspondencia no quedó
verificada. El intento de consultar el catálogo REST público no produjo un
resultado verificable y se canceló la espera. No se considera validación del hosting.

## Cambios implementados

El orden principal ahora es fotografías, motivo, fecha/hora automática,
análisis y guardado de borrador. Los campos manuales opcionales quedan después.
La fecha y hora se inicializan al abrir una inspección nueva y se conservan
al recuperar un borrador; se mantiene la corrección manual existente.

Se recomienda comenzar por las puertas traseras, sin exigir esa imagen ni un
orden de selección. Se mantienen cámara, galería, miniaturas y recuperación.

El botón «Enviar y analizar fotografías» muestra requisitos locales y el
bloqueo del servicio. Con foto y motivo, el estado es servicio no disponible,
incluso si identificador, ISO y observaciones están vacíos. No se muestran
estados ficticios de envío, procesamiento o resultado.

El cálculo de preparación es puro. Adjuntar fotos, elegir motivo o recomponer
la pantalla no ejecuta análisis ni guardado. Un diario de importación correcta
conservado para recuperación no se presenta como error; una selección fallida
o captura pendiente sí requiere atención.

«Guardar borrador en este teléfono» conserva su operación independiente,
sin dependencia de conexión o IA. No requiere foto, motivo ni campos escritos
para conservar un borrador incompleto.

| Archivo de esta tarea | Cambio |
| --- | --- |
| [InspectionFormScreen.kt](../app/src/main/java/com/mucacran/infocontainer/ui/InspectionFormScreen.kt) | Reordenación y sección manual opcional; conserva guardado y recuperación. Retira el aviso de formulario incompleto que inducía a escribir antes de continuar. |
| [PhotographsSection.kt](../app/src/main/java/com/mucacran/infocontainer/ui/PhotographsSection.kt) | Inicio sin escritura y recomendación de puertas traseras. |
| [PhotoAnalysisSection.kt](../app/src/main/java/com/mucacran/infocontainer/ui/PhotoAnalysisSection.kt) | Nueva sección, botón deshabilitado, motivos comprensibles y estado accesible. |
| [PhotoAnalysisReadiness.kt](../app/src/main/java/com/mucacran/infocontainer/ui/PhotoAnalysisReadiness.kt) | Preparación local sin efectos ni red. |
| [strings.xml](../app/src/main/res/values/strings.xml) | Etiquetas opcionales, orientación y mensajes de estado. |
| [PhotoAnalysisReadinessTest.kt](../app/src/test/java/com/mucacran/infocontainer/ui/PhotoAnalysisReadinessTest.kt) | Diez pruebas JVM nuevas. |
| [PhotoFormLifecycleTest.kt](../app/src/androidTest/java/com/mucacran/infocontainer/ui/PhotoFormLifecycleTest.kt) | Ajusta el orden de desplazamiento al nuevo formulario. |
| Este informe | Diagnóstico, evidencia, limitaciones y siguiente paso. |

## Verificaciones y resultados

| Verificación ejecutada | Resultado |
| --- | --- |
| Preparación del análisis, estado del formulario/fotos, repositorio y validación | 53 pruebas JVM aprobadas; 0 fallos y 0 omitidas en la ejecución final. |
| Transportes IA y protección de identificación existentes | 18 pruebas JVM aprobadas; 0 fallos y 0 omitidas. Dobles y HTTPS local simulado. |
| `:app:assembleDebug` | Correcto. |
| `:app:assembleDebugAndroidTest` | Correcto; compila las pruebas de dispositivo, sin ejecutarlas. |
| `:app:lintDebug` | 0 errores y 16 advertencias. |
| Inspecciones IDE de las secciones del formulario | Sin errores; sugerencias de estilo. |
| `git diff --check` | Correcto; avisos de conversión LF/CRLF del entorno. |
| Revisión adicional del flujo | Botón sin transporte, estado puro y guardado independiente; detectó y corrigió la advertencia falsa del diario de importación. |

Las diez pruebas nuevas ejercitan inicio sin escritura y fecha automática,
foto/motivo sin identificador ni ISO, cambios sin guardado automático,
selector/cámara/importación pendientes, diario completado, carga y errores,
conservación de fotos ante fallo de guardado y protección frente a pulsaciones
repetidas. El guardado exitoso conserva un solo ID y actualiza ese mismo borrador.

Las pruebas de transportes existentes cubren rechazo HTTP, timeout, TLS,
redirecciones, ausencia de reintentos automáticos y bloqueo previo al transporte.
Estas respuestas proceden de pruebas locales: no acreditan una operación real
del nuevo formulario contra WordPress.

Comandos finales ejecutados mediante Gradle de Android Studio:

```text
:app:testDebugUnitTest --tests com.mucacran.infocontainer.ui.PhotoAnalysisReadinessTest --tests com.mucacran.infocontainer.ui.InspectionFormStateTest --tests com.mucacran.infocontainer.ui.PhotoFormStateTest --tests com.mucacran.infocontainer.data.InspectionDraftRepositoryTest --tests com.mucacran.infocontainer.util.ContainerValidatorTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain

:app:testDebugUnitTest --tests com.mucacran.infocontainer.network.WordPressAiApiTest --tests com.mucacran.infocontainer.data.AiIdentificationCoordinatorSafetyTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain
```

No se instalaron APKs, no se ejecutaron pruebas en dispositivo y no se accedió
a la base ni a las fotografías del teléfono. La validación nueva de persistencia
usa repositorios de prueba; no sustituye una prueba SQLite o de cámara/galería
en dispositivo. No se hicieron commits, push ni despliegues.
No se ejecutaron solicitudes de identificación/análisis/revisión reales ni
llamadas reales de pago a OpenAI.

## Estado de los criterios de finalización

- Implementado y probado por JVM: inicio sin escritura, motivo mediante botones,
  fecha automática, preparación sin campos manuales y guardado local independiente.
- Conservado y compilado: captura, galería, recuperación y pruebas de ciclo de vida.
  Su ejecución en dispositivo queda pendiente; no se declara verificación visual.
- Verificado por revisión de código y estado puro: agregar fotografías no dispara
  análisis. El botón está deshabilitado y no tiene transporte asociado.
- Verificado con dobles: guardado sin dependencia de red y conservación ante errores.
- Bloqueado: envío/análisis real, resultados reales revisables dentro de este
  formulario y guardado definitivo. La demostración de Conexión sigue siendo
  simulada y no analiza las fotos locales del nuevo formulario.
- La herramienta de identificación sintética real existente sigue separada y no
  se ejecutó en esta tarea. No se añadieron simulaciones nuevas.

## Siguiente paso recomendado

1. Obtener el código o contrato vigente del plugin WordPress y confirmar sus
   capacidades, permisos, límites y respuestas asíncronas.
2. Resolver una capacidad de borrador remoto sin identificación previa, o su
   asociación segura tras identificar y revisar. Definir altas recuperables por
   UUID y una operación inequívoca de guardado definitivo, sin inventar ahora
   nombres de endpoints.
3. Persistir la intención de análisis y revisión antes del POST, recuperar el
   mismo intento tras cierre/timeout y consultar su estado sin crear otro.
   Alinear límites y formatos con las fotografías locales conservando originales.
4. Acordar presupuesto y autorización explícita para IA. Después conectar el botón
   con pulsación única, progreso real y revisión editable de identificador, ISO y
   observaciones antes de finalizar.
5. Validar cámara, galería, recuperación, modo sin conexión y errores en dispositivo
   sin usar servicios de pago; validar después el servidor con un ensayo acotado
   autorizado.

Las fotografías y las propuestas requieren revisión humana. No certifican
hermeticidad ni condiciones estructurales.
