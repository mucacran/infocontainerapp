# InfoContainer (App Android)

Sistema de registro e inspección de contenedores para Mucacrán S.A.S.

## Información del Proyecto
- **Ruta Local Activa:** `C:/proyectosMucacran/infocontainerapp`
- **Lenguaje:** Kotlin 2.2.10
- **Sistema de UI:** Jetpack Compose (Material 3)
- **Application ID:** `com.mucacran.infocontainer`
- **SDK Mínimo:** 26 (Android 8.0)
- **SDK Objetivo:** 36
- **Gradle Wrapper:** 9.4.1
- **Android Gradle Plugin (AGP):** 9.2.1

---

## Pasos para Compilación, Pruebas y Ejecución

### 1. Compilar Versión Debug
Desde la terminal en la raíz del proyecto:
```bash
./gradlew assembleDebug
```

### 2. Ejecutar Pruebas Automatizadas y Lint
- **Pruebas Unitarias JVM:**
  ```bash
  ./gradlew testDebugUnitTest
  ```
- **Pruebas de Integración con SQLite Real (Dispositivo/Emulador):**
  ```bash
  ./gradlew connectedDebugAndroidTest
  ```
- **Verificación Estática Lint:**
  ```bash
  ./gradlew lintDebug
  ```

### 3. Ejecutar en Dispositivo
```bash
./gradlew installDebug
adb shell am start -n com.mucacran.infocontainer/.MainActivity
```

---

## Estado Actual e Implementación de Borradores Locales

### Funcionalidades Implementadas
1. **Formulario de Nueva Inspección y Edición:**
   - **Identificador de Contenedor:** Normalización en tiempo real (eliminación de espacios/guiones, conversión a mayúsculas, conservación de ceros iniciales) y validación de formato ISO 6346 (4 letras finalizando en 'U', 7 dígitos y cálculo exacto del dígito de control).
   - **Código ISO (opcional):** Normalización y validación de 4 caracteres alfanuméricos.
   - **Motivo de Inspección:** Opciones en español (*Inventario, Venta, Recepción, Entrega*) sin selección predeterminada (requiere selección explícita del usuario).
   - **Fecha y Hora de Inspección:** Inicialización con la fecha/hora del dispositivo y selectores integrados (*DatePicker* y *TimePicker*) para edición precisa.
   - **Observaciones (opcionales):** Control de longitud con contador Unicode en puntos de código (*code points*) hasta un límite estricto de 10.000 caracteres.

2. **Persistencia Privada con SQLite Real:**
   - Implementado mediante `SQLiteOpenHelper` nativo (`infocontainer.db`), garantizando almacenamiento estructurado, relacional y persistente sin dependencias pesadas.
   - **Operaciones Asíncronas:** Lectura, guardado y eliminación ejecutadas fuera del hilo UI principal con Kotlin Coroutines en `Dispatchers.IO` para evitar congelamientos de la interfaz.
   - **Manejo Seguro de Carga y Edición (`FormLoadState`):** Diferenciación clara entre estado de carga (*Loading*), borrador encontrado (*Success*), borrador no encontrado (*NotFound*) y error de I/O (*Error*). Se bloquea la sobrescritura accidental en caso de fallo de lectura.
   - **Creación y Edición Separadas:** Las altas usan `INSERT` estricto. Las ediciones usan `UPDATE WHERE id = ?` y exigen una fila afectada; si el borrador desapareció, se informa el error y se conserva el formulario sin recrearlo.
   - **Guardado Protegido:** Durante la escritura se bloquean campos, motivo, fecha/hora, guardado repetido y salida. La instantánea normalizada enviada a SQLite pasa a ser la referencia solo tras éxito; un fallo conserva contenido y referencia anterior.
   - **Detección Fiel de Cambios Sin Guardar:** Comparación estructural completa campo a campo (`FormContent`), evitando colisiones de hash (ej. *"Aa"* vs *"BB"*). El diálogo de confirmación se activa al intentar salir con cambios pendientes.
   - **Política de Versiones:** El esquema actual es v2. La migración real v1→v2 añade únicamente `inspection_photos`, conservando la tabla y los registros anteriores. Cualquier otra transición se rechaza sin adelantar la versión ni borrar datos.

3. **Lista de Borradores Guardados:**
   - Sustitución de la pantalla provisional por `DraftListScreen`.
   - Distinción entre lista vacía y error de almacenamiento (con opción de reintento).
   - Identificación visual de los registros mediante la etiqueta **"Solo en este teléfono"** e indicador de completitud del borrador (*Borrador completo* vs *Borrador incompleto*).

---

## Configuración de Respaldo Android (Backup Policy)

Actualmente la aplicación cuenta con la siguiente configuración en `AndroidManifest.xml`:
- `android:allowBackup="true"`
- `android:dataExtractionRules="@xml/data_extraction_rules"`
- `android:fullBackupContent="@xml/backup_rules"`

### Qué incluye actualmente:
- La política previa sigue permitiendo respaldar la base SQLite privada (`/databases/infocontainer.db`). No se amplió deliberadamente su inclusión en nube.
- Las fotografías y los diarios de importación se guardan en `noBackupFilesDir` y quedan fuera del respaldo Android. Las capturas temporales `files/camera_capture/` se excluyen explícitamente en `backup_rules.xml` (API 26–30) y en las reglas de nube y transferencia de Android 12+.
- Una restauración de la BD sin fotografías puede recuperar metadatos cuyos archivos ya no estén en ese teléfono. La interfaz muestra “Fotografía no disponible”; es posible quitar esa referencia o seleccionar de nuevo el original. El guardado no crea referencias nuevas a archivos ausentes.
- Estos XML y `allowBackup="true"` permiten el respaldo Android; no demuestran que se haya realizado una copia ni una restauración. Ambas comprobaciones siguen pendientes y dependen del sistema y de la configuración del dispositivo.
- **Nota:** La política definitiva de respaldo (si excluir o mantener la BD local en Google Cloud Backup) queda pendiente de decisión formal por parte de Sergio y el equipo.

---

## Resultados Previos Reportados por Gemini

Estos resultados corresponden al estado anterior a las correcciones de Codex. No acreditan la ejecución de las pruebas nuevas.

1. **Pruebas Automatizadas Unitarias (JVM):**
   - **21 pruebas unitarias superadas al 100%** (`:app:testDebugUnitTest`).
   - Incluían normalización de contenedores, cálculo de dígito de control ISO 6346, límite Unicode y comparación *"Aa"* → *"BB"*. Las pruebas anteriores llamadas de estado de formulario solo usaban mocks y no demostraban conservación de la interfaz ni sus bloqueos.

2. **Pruebas de Integración con SQLite Real (Dispositivo Físico):**
   - **5 pruebas instrumentadas superadas al 100%** (`:app:connectedDebugAndroidTest`) en el teléfono físico autorizado (`ANNXVB5708002966`).
   - Verificaban persistencia SQLite, reapertura, edición con fila presente y eliminación. La antigua prueba de migración llamaba directamente a `onUpgrade` y no acreditaba una apertura real con rechazo ni conservación de versión.

3. **Verificación Estática Lint:**
   - **0 errores y 11 advertencias en `lintDebug`** (`:app:lintDebug`): nueve de versiones/dependencias, una de API objetivo y una de recurso sin usar.

4. **Verificación Visual e Interacción Real en Teléfono:**
   - Despliegue confirmado en el dispositivo físico `ANNXVB5708002966`.
   - Creación de borradores incompletos y completos, persistencia tras cerrar y reabrir la app, recuperación desde la lista, edición sin generación de duplicados y despliegue del diálogo *"Cambios sin guardar"* al presionar regresar con modificaciones.

---

## Continuidad anterior de Codex — protección de borradores (histórico)

Este bloque conserva la evidencia anterior a incorporar fotografías. Sus referencias a esquema v1, APK no instalado y rotación pendiente describen aquella revisión; el esquema y los resultados vigentes se indican en Fotografías locales y en su validación actual.

### Correcciones implementadas

- `InspectionFormState`, utilizado por el formulario, mantiene contenido, referencia y operaciones en un `ViewModel` de la Activity. No vuelve a cargar un borrador ya inicializado al recomponer, al recrear la pantalla o después del primer guardado.
- La escritura bloquea todas las entradas, selectores y navegación; una segunda solicitud se ignora. Solo el éxito confirma la instantánea normalizada enviada. El error conserva el contenido y la referencia anterior, desbloquea la edición y muestra un mensaje; si falta la fila, el mensaje lo distingue de un error genérico.
- El repositorio separa `createDraft` y `updateDraft`, conserva los timestamps de la instantánea y no usa reemplazo ni alta alternativa al editar.
- En esa revisión, `onUpgrade` rechazaba versiones no soportadas y producción continuaba en v1, sin borrar tablas ni registros. La etapa de fotografías posterior implementa la migración aditiva v1→v2.

### Verificaciones ejecutadas por Codex

En el proyecto local, con JBR 21 de Android Studio y dependencias ya disponibles, terminó correctamente:

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest --tests 'com.mucacran.infocontainer.ui.InspectionFormStateTest' --tests 'com.mucacran.infocontainer.data.InspectionDraftRepositoryTest' --tests 'com.mucacran.infocontainer.util.UnsavedChangesDetectionTest' :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
```

- **20 pruebas unitarias afectadas: 0 fallos, 0 errores, 0 omitidas.** Son 14 pruebas del estado de producción que usa la UI, 3 de igualdad de contenido y 3 del repositorio de prueba adaptado a la nueva API. Las 14 de estado ejercitan carga/error/inexistencia, escritura demorada, bloqueo de edición y navegación, escritura única, instantánea normalizada, conservación ante fallos, actualización sin alta alternativa y ausencia de recarga tras crear. No se presentan los dobles de almacenamiento como evidencia de SQLite real.
- **`assembleDebug`: correcto.** APK generado localmente; no instalado.
- **`lintDebug`: 0 errores y 11 advertencias.** Siete `GradleDependency`, una `AndroidGradlePluginVersion`, una `NewerVersionAvailable`, una `OldTargetApi` y una `UnusedResources` (`btn_delete_draft`). No se actualizaron dependencias para eliminar avisos.
- **7 pruebas instrumentadas ejecutadas y aprobadas (0 fallos):** 6 SQLite reales y 1 de contexto. La migración abre una BD v1 con datos, solicita v2 mediante un helper de prueba que usa la política de rechazo, espera el fallo y reabre v1 para comprobar registros, SQL de creación, columnas y versión. Las 3 pruebas Compose exploratorias intentadas fallaron antes de ejecutar la pantalla porque el runner físico no mantuvo una jerarquía Compose host para `setContent`; se retiró ese arnés y no se presenta como evidencia aprobada.

### Pendientes y alcance de la evidencia

- **Las instrumentadas SQLite nuevas sí se ejecutaron en el teléfono y pasaron.** La comprobación visual Compose y la rotación no quedaron automatizadas. La instalación fue incremental (`adb install -r`), sin desinstalar ni borrar datos; no se tocó `infocontainer.db`.
- Las bases de prueba usan nombres únicos; no abren `infocontainer.db`. El cierre de las pruebas solo cierra conexiones, sin borrar archivos de bases. Los casos de eliminación actúan únicamente sobre sus registros sintéticos cuando se ejecuten.
- El estado retenido prepara la conservación durante cambios de configuración; las unitarias no equivalen a una rotación real. No se declara verificada la restauración tras muerte del proceso Android.
- No se realizó ni se comprobó respaldo o restauración Android.

### Pasos manuales pendientes de rotación

Instrucciones de aquella revisión (la instalación debug ya fue autorizada):

1. **Borrador nuevo:** abrir Nueva inspección, escribir `CSQU3054383`, ISO `45G1`, seleccionar Inventario, cambiar fecha/hora y escribir `Rotación nuevo 📦` en observaciones. Anotar la fecha/hora elegida y no guardar.
2. Girar a horizontal y regresar a vertical. Comprobar identificador, ISO, motivo, fecha/hora y observaciones. Pulsar Atrás: debe aparecer “Cambios sin guardar”. Elegir “Continuar editando” y comprobar que conserva todos los valores.
3. **Borrador existente:** abrir uno guardado; cambiar observaciones a `Rotación existente 📦`, seleccionar otro motivo y cambiar fecha/hora, sin guardar. Anotar también identificador e ISO.
4. Repetir ambos giros y verificar todos los campos. Pulsar Atrás: debe aparecer la advertencia; “Continuar editando” debe conservar la edición y el mismo borrador. No eliminar el registro ni descartar sus datos para realizar esta comprobación.

En aquella revisión, la verificación visual Android y ambos casos de rotación quedaban pendientes. La validación actual de fotografías, más abajo, sustituye ese estado de continuidad.

---

## Fotografías locales — implementación actual

La sección Fotografías aparece antes de los campos manuales. Permite comenzar con una sola imagen, añadir más, revisar miniaturas, abrir una vista ampliada y quitar adjuntos. No exige número de contenedor. Las fotos deben pertenecer al mismo contenedor y se identifican como locales, sin análisis ni envío.

- **Cámara:** `ActivityResultContracts.TakePicture` abre la cámara del sistema con una URI de salida completa. No se usa la miniatura del resultado ni se pide permiso de cámara a la app. El `FileProvider` no se exporta y únicamente expone `files/camera_capture/`; no expone el resto del almacenamiento privado.
- **Selector:** selección múltiple de imágenes con `PickMultipleVisualMedia`; AndroidX utiliza el selector disponible y recurre a documentos del sistema en dispositivos compatibles con el mínimo API 26. No se piden permisos amplios de lectura de galería. Sólo se procesan las imágenes que el usuario selecciona.
- **Originales:** copia byte por byte a `noBackupFilesDir/inspection_photos/`, sin modificar, comprimir ni eliminar el original de galería. Copia, validación y decodificación se ejecutan en `Dispatchers.IO`. Se valida imagen legible y MIME real JPEG, PNG o WebP. HEIC/HEIF, GIF y otros formatos se rechazan con un mensaje para seleccionar un formato admitido.
- **Límites:** 12 fotos por borrador, 30 MiB por foto, 150 MiB en total y 40 millones de píxeles por imagen. Se reserva un margen libre de 16 MiB y se informa de fallos de espacio o lectura. Las vistas usan decodificación reducida (320 píxeles de lado máximo para miniaturas y 1600 para ampliación) y respetan la orientación EXIF; el archivo completo no cambia.
- **Persistencia:** SQLite guarda IDs locales y metadatos ordenados, no bitmaps ni URIs externas. Se copian y validan los archivos antes de vincularlos; campos y vínculos se escriben en una sola transacción. Un error revierte los cambios SQLite y conserva el formulario y sus fotos para reintentar. Editar una fila inexistente nunca la recrea.
- **Ciclo de vida:** `SavedStateHandle` conserva contenido, referencia, IDs de fotos, sesión y captura pendiente al recrearse la Activity/proceso desde el estado Android. Un diario `AtomicFile` privado registra URIs seleccionadas, IDs reservados y progreso; permite recuperar copias terminadas y reintentar las pendientes, con permisos persistentes cuando el proveedor los admite. Las copias terminadas ya no dependen de la URI externa. Una concesión perdida en una copia aún pendiente se informa para volver a seleccionar; no se examinan otras fotos del teléfono.
- **Guardado y descarte:** adjuntar o quitar cambia el estado pendiente; únicamente Guardar confirma la referencia persistida. Descartar conserva las fotos que pertenecían a la referencia guardada. Quitar una foto guardada no borra su archivo privado hasta confirmar el guardado del cambio. La limpieza usa sólo UUID propios con consulta previa de referencias; una consulta o limpieza dudosa conserva el archivo. No hay barrido general de galería, bases ni archivos del dispositivo.
- **Cancelación:** cancelar cámara/selector no crea filas ni adjuntos vacíos. Una captura cuya importación falla conserva su temporal para Reintentar o Quitar captura pendiente. Una selección parcialmente copiada conserva las imágenes válidas y ofrece reintento. Cerrar una selección pendiente retira sus metadatos/permisos, sin borrar originales.
- **Cierre/reapertura:** después de Guardar, texto y fotografías se recuperan desde SQLite y archivos privados al abrir el borrador. La restauración de cambios sin guardar depende del estado de instancia entregado por Android; no equivale a guardar un borrador ni garantiza recuperación tras retirar la tarea, borrar almacenamiento o desinstalar.

Las bases, capturas y carpetas de fotos/diarios están excluidas de Git. No se añadieron servicios de red ni dependencias para análisis o transferencia.

### Validación actual de fotografías — 15 de octubre de 2026

- Carpeta comprobada: `C:/proyectosMucacran/infocontainerapp`. Se conservaron los cambios existentes, incluido el commit `68cd40a` que apareció durante el trabajo; Codex no creó commits ni hizo push.
- **36 unitarias afectadas aprobadas, 0 fallos:** 14 de protección del formulario, 16 de fotografías/estado restaurable, 3 de igualdad de contenido y 3 del repositorio de prueba. Se ejecutaron con JBR 21 y `--offline`; las pruebas del repositorio de prueba no sustituyen SQLite real.
- **Compilación:** `assembleDebug` y `assembleDebugAndroidTest` correctos. **`lintDebug`: 0 errores y 20 advertencias**: 7 `GradleDependency`, 1 `AndroidGradlePluginVersion`, 1 `NewerVersionAvailable`, 1 `OldTargetApi`, 1 `UnusedResources`, 2 `ExifInterface`, 1 `UsableSpace` y 6 `UseKtx`. Las nueve adicionales recomiendan AndroidX EXIF, APIs de espacio y extensiones Kotlin; la prueba de orientación pasó en API 36 y la copia usa un margen libre conservador. Siguen siendo recomendaciones técnicas; no se actualizaron dependencias para ocultarlas. El compilador también avisa del destino futuro de `@StringRes` en `InspectionType`, fuera del recuento Lint.
- **Teléfono físico:** único dispositivo autorizado `ANNXVB5708002966` (ABR‑NX3, API 36). Instalación incremental de ambos APK con `adb install -r -t`: correcta, sin desinstalar ni borrar datos.
- **39 casos instrumentados distintos aprobados, 0 fallidos en la última ejecución de cada caso:** 19 de archivos originales, orientación, límites, cancelación, recuperación del diario y limpieza; 14 de SQLite real, asociaciones, transacciones, edición inexistente, migración aditiva v1→v2 y rechazo de intento v2→v3 mediante apertura real; 5 de interfaz real; y 1 de contexto. La v3 de rechazo es sólo un intento de prueba, sin esquema histórico ni migración implementada.
- **Rotación automatizada en teléfono aprobada, nuevo y existente:** edición por accesibilidad, giro real a horizontal y vertical, comparación de todos los campos/IDs con la instantánea anterior, lectura de campos y miniaturas en la UI, y aviso real “Cambios sin guardar”. También pasaron descarte conservando el original guardado, recreación de Activity (sin afirmar muerte del proceso), guardado/cierre/reapertura con bytes y metadatos idénticos, y Guardar desde el aviso sin salir mientras queda una captura por reintentar.
- **Aislamiento comprobado:** bases `infocontainer_real_test_UUID.db` / `form_photo_ui_test_UUID.db`, imágenes sintéticas y almacenamiento bajo carpetas UUID de caché. La limpieza de UI/archivos sólo alcanza esos fixtures; no abre ni elimina `infocontainer.db`, originales reales ni galería. Las pruebas SQLite cierran conexiones y conservan sus bases de prueba.
- **Conservación en el teléfono:** antes y después de instalación y pruebas se compararon huellas de todas las filas de la BD de producción y del original vinculado, sin visualizar la imagen. El borrador y su fotografía existentes permanecieron idénticos; `integrity_check` devolvió `ok`. La BD del teléfono ya estaba en v2; la migración v1→v2 se acredita con el fixture histórico separado.
- **Arnés de UI y reintentos:** el intento anterior en emulador API 37 falló en cuatro pruebas antes del cuerpo por `Espresso 3.5.1 / InputManager.getInstance`. Se sustituyó el arnés por `ActivityScenario`, accesibilidad y gestos Android nativos sincronizados a frames, sin nuevas dependencias. En el teléfono hubo **48 ejecuciones: 39 aprobadas y 9 fallidas del arnés en intentos anteriores** (39 iniciales: 34/5; repetición de 5: 1/4; repetición sólo de las 4 restantes: 4/0). No se repitieron los casos ya superados. Al consolidar el resultado más reciente de cada caso: **39/39 aprobados**. Los logs conservan también los intentos fallidos.
- **Ajustes finales revisados:** la recuperación del diario espera al formulario cargado y al resultado externo para no reemplazar la solicitud del selector; Guardar no abandona una captura pendiente; el importador aplica cantidad/presupuesto durante la copia y expone las copias válidas de un lote parcial; los pendientes activan el aviso al salir aun sin cambios textuales.

Comandos de compilación y unitarias (sin acceso a red):

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest --tests 'com.mucacran.infocontainer.ui.*' --tests 'com.mucacran.infocontainer.data.InspectionDraftRepositoryTest' --tests 'com.mucacran.infocontainer.util.UnsavedChangesDetectionTest' --console=plain
.\gradlew.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
adb -s ANNXVB5708002966 shell am instrument -w -r com.mucacran.infocontainer.test/androidx.test.runner.AndroidJUnitRunner
```

Los logs locales están en `app/build/reports/` (excluidos de Git); `photos-verification-summary.json` consolida casos, ejecuciones y unitarias. La automatización usa fotografías sintéticas; no sustituye la comprobación óptica ni la selección humana desde la galería. En el teléfono físico `ANNXVB5708002966` se cancelaron cámara y selector sin crear adjuntos. Sergio confirmó dos fotos en cada sesión: el borrador nuevo pasó de 1 a 4 fotografías y el borrador existente de 2 a 4, siempre como cambios sin guardar antes de la interrupción. Tras `force-stop`, ambos mostraron Recuperar; se verificó el conteo 4/12, se guardó una sola vez y se reabrieron con sus asociaciones SQLite. La rotación y el guardado/reapertura indicados arriba sí quedaron automatizados en el teléfono; no se presentan como comprobaciones visuales humanas.

Como comprobación de interrupción se ejecutó `am force-stop` y reapertura de la Activity con ese diario pendiente. La causa fue que el diario no tenía un registro durable del formulario, su referencia y el `photoSessionId`; la Activity siempre iniciaba en Inicio. Se implementó `FormRecoveryStore` en `noBackupFilesDir/inspection_form_recovery.json`, con escritura AtomicFile fuera del hilo principal y diálogo explícito Recuperar/Descartar. Tras la corrección, el borrador nuevo y el existente recuperaron campos, miniaturas y sesiones, se guardaron una sola vez y se reabrieron con las asociaciones SQLite presentes. `force-stop` no se presenta como terminación espontánea del sistema. No se borraron datos ni se desinstaló la aplicación.

Clasificación de las 20 advertencias actuales de Lint: 9 son disponibilidad futura de herramientas o dependencias (7 `GradleDependency`, `AndroidGradlePluginVersion` y `NewerVersionAvailable`); 1 es compatibilidad de `targetSdk` (`OldTargetApi`); 2 recomiendan AndroidX para EXIF, relevante para orientación y compatibilidad; 1 recomienda `StorageManager` para considerar caché liberable, relevante para el margen de espacio; 6 son equivalencias KTX (`toUri`, `scale`, `createBitmap`); y 1 es un recurso sin uso. Ninguna concede permisos de cámara o almacenamiento, expone archivos, cambia la política de respaldo o implica una fuga de privacidad. Las dos advertencias de almacenamiento/EXIF son las únicas nuevas con relación directa a esta etapa y fueron cubiertas con validaciones y pruebas en API 36; no se cambiaron dependencias sólo para silenciarlas.

### Comprobaciones manuales de cámara y selector en teléfono

Usar únicamente imágenes de prueba propias. La instalación autorizada debe ser incremental (`adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`); si Android exige desinstalar o borrar datos, detenerse.

1. Abrir Nueva inspección sin escribir un contenedor. Cancelar cámara y selector: la cantidad debe seguir en cero y no aparecer un borrador guardado nuevo.
2. Tomar una foto de un objeto de prueba. Verificar miniatura y ampliación con orientación correcta. Añadir dos imágenes de prueba desde el selector; comprobar cantidad y contenido. No explorar fotografías personales ajenas a la prueba.
3. Cambiar campos manuales, motivo y fecha/hora. Girar a horizontal y volver a vertical. Comprobar campos y fotos; Atrás debe mostrar “Cambios sin guardar”. Continuar editando debe conservar todo.
4. Guardar, cerrar la aplicación y volver a abrir ese borrador. Comprobar texto y las tres imágenes. Quitar una foto y salir con Descartar: al reabrir debe seguir guardada y el original de galería debe permanecer intacto.
5. En ese borrador existente, cambiar observaciones y adjuntar otra imagen sin guardar. Repetir ambos giros y comprobar la advertencia al salir. Guardar la edición y reabrir; comprobar las mismas imágenes y que no se duplicó el borrador.
6. Para recreación del proceso mientras está abierta la cámara/selector: con la app externa en primer plano, ejecutar `adb -s SERIAL shell am kill com.mucacran.infocontainer` (sin `pm clear` ni desinstalación); volver con una imagen de prueba o cancelar y comprobar campos, referencia, IDs y ausencia de duplicados. Esta comprobación es distinta de girar o recrear únicamente la Activity.

La comprobación de respaldo/restauración sigue pendiente. La política conjunta de BD respaldable y fotografías excluidas requiere decisión del equipo; esta etapa no realiza copias ni restauraciones.

## Limitaciones y Siguiente Paso

### Limitaciones Actuales
- Esta etapa **NO** realiza llamadas de red, sincronización ni autenticación con la API REST de WordPress.
- La app escribe borradores en su almacenamiento privado local; Android puede incluirlos en respaldos según la configuración descrita arriba. No existe sincronización propia hacia WordPress.
- Se incluye captura y selección local de fotografías. El análisis por IA y la certificación de condición estructural permanecen fuera del alcance.

### Siguiente Paso
- Completar las verificaciones Android pendientes antes de cerrar esta etapa. El contrato REST con WordPress (módulos `InfoContainer_Validator` e `InfoContainer_REST_API`) permanece como trabajo posterior, fuera de este encargo.
