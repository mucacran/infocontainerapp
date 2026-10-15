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
   - **Política de Versiones:** El esquema de producción sigue en v1. Toda transición no soportada se rechaza explícitamente; no hay una migración v2 implementada. La prueba de apertura con v2 es únicamente un intento sintético para comprobar el rechazo y la conservación de datos, estructura y versión.

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
- En Android 12+ (API 31+), `data_extraction_rules.xml` contiene `cloud-backup` sin exclusiones activas; `device-transfer` está comentado. La base SQLite privada (`/databases/infocontainer.db`) queda permitida por la configuración de respaldo.
- En API 26 a 30, `backup_rules.xml` tampoco contiene exclusiones activas de la base de datos.
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

## Continuidad Codex — 15 de octubre de 2026

### Correcciones implementadas

- `InspectionFormState`, utilizado por el formulario, mantiene contenido, referencia y operaciones en un `ViewModel` de la Activity. No vuelve a cargar un borrador ya inicializado al recomponer, al recrear la pantalla o después del primer guardado.
- La escritura bloquea todas las entradas, selectores y navegación; una segunda solicitud se ignora. Solo el éxito confirma la instantánea normalizada enviada. El error conserva el contenido y la referencia anterior, desbloquea la edición y muestra un mensaje; si falta la fila, el mensaje lo distingue de un error genérico.
- El repositorio separa `createDraft` y `updateDraft`, conserva los timestamps de la instantánea y no usa reemplazo ni alta alternativa al editar.
- `onUpgrade` rechaza versiones no soportadas. Producción continúa en v1, sin borrar tablas ni registros.

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

Una vez autorizada la instalación de esta revisión:

1. **Borrador nuevo:** abrir Nueva inspección, escribir `CSQU3054383`, ISO `45G1`, seleccionar Inventario, cambiar fecha/hora y escribir `Rotación nuevo 📦` en observaciones. Anotar la fecha/hora elegida y no guardar.
2. Girar a horizontal y regresar a vertical. Comprobar identificador, ISO, motivo, fecha/hora y observaciones. Pulsar Atrás: debe aparecer “Cambios sin guardar”. Elegir “Continuar editando” y comprobar que conserva todos los valores.
3. **Borrador existente:** abrir uno guardado; cambiar observaciones a `Rotación existente 📦`, seleccionar otro motivo y cambiar fecha/hora, sin guardar. Anotar también identificador e ISO.
4. Repetir ambos giros y verificar todos los campos. Pulsar Atrás: debe aparecer la advertencia; “Continuar editando” debe conservar la edición y el mismo borrador. No eliminar el registro ni descartar sus datos para realizar esta comprobación.

El cierre de verificación visual Android y ambos casos de rotación queda pendiente de realizar manualmente. Los tres defectos tienen corrección de código y las comprobaciones instrumentadas SQLite y unitarias indicadas arriba.

---

## Limitaciones y Siguiente Paso

### Limitaciones Actuales
- Esta etapa **NO** realiza llamadas de red, sincronización ni autenticación con la API REST de WordPress.
- La app escribe borradores en su almacenamiento privado local; Android puede incluirlos en respaldos según la configuración descrita arriba. No existe sincronización propia hacia WordPress.
- No se incluye captura de fotografías, análisis por IA ni certificación de condición estructural del contenedor.

### Siguiente Paso
- Completar las verificaciones Android pendientes antes de cerrar esta etapa. El contrato REST con WordPress (módulos `InfoContainer_Validator` e `InfoContainer_REST_API`) permanece como trabajo posterior, fuera de este encargo.
