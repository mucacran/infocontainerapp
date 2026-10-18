# Informe y revisiones del análisis simulado — 18 de octubre de 2026

## Causa comprobada

`AiAnalysisState.runSimulated()` guardaba la respuesta del coordinador en `AiAnalysisState.run`. Sin embargo, `AiAnalysisPanel` sólo componía detalles, editor y botones dentro de `demo.analysis?.let`. Esa propiedad pertenece a otra demostración y permanecía nula al ejecutar únicamente «Ejecutar flujo simulado». El estado de éxito era verdadero, pero no existían controles debajo. La columna de `ConnectionScreen` ya tenía `verticalScroll`: alcanzaba el final del contenido realmente compuesto.

## Corrección

- El panel usa exclusivamente `analysisState.run.proposal`. El informe editable vive en ese mismo ViewModel y se inicializa al recibir la propuesta, nunca al recomponer.
- Las decisiones pasan por el coordinador. Se retiró la confirmación local optimista de `demo.review`; sólo la respuesta del transporte confirma la revisión.
- La edición conserva los diez campos estructurados del contrato, incluidas las listas y los IDs reales de la propuesta, cambiando sólo `report_draft`.
- Aceptar, Registrar edición y Rechazar ocupan filas de ancho completo con un mínimo de 48 dp. También se corrigieron las filas estrechas de la demostración anterior.
- La revisión bloquea pulsaciones simultáneas, informa éxito o excepción y restablece el procesamiento en `finally`. Un transporte suspendido termina con un error visible después de 15 segundos (2 segundos en el arnés de prueba).
- El coordinador se adjunta una sola vez al ViewModel y conserva sus UUID durante la recreación de Activity. El falso registra una revisión por UUID y rechaza contenido diferente bajo el mismo UUID.
- `AiAnalysisCoordinator.simulated()` sólo admite las dos clases falsas concretas, sin cliente HTTP ni delegación a producción. No necesita autorizar la barrera: utiliza `AiExecutionGate(false)`. La ruta de producción sigue bloqueada y la demostración se inyecta sólo en debug.

## Archivos

Producción: `ui/AiAnalysisPanel.kt`, `ui/AiAnalysisState.kt`, `data/AiAnalysisCoordinator.kt`, `network/SimulatedAiTransports.kt`, `ui/ConnectionScreen.kt`, `ui/AiDemoPanel.kt` y `MainActivity.kt`.

Pruebas: `src/debug/.../ui/ConnectionNavigationTestActivity.kt` (arnés existente ampliado), `src/androidTest/.../ui/AiAnalysisUiRegressionTest.kt`, `src/test/.../data/AiAnalysisCoordinatorRegressionTest.kt`. No se añadió una pantalla de producto.

## Repetición física

1. Abrir InfoContainer → Conexión. No hacen falta credenciales para la demostración.
2. Desplazarse a «Análisis de inspección remota» y pulsar «Ejecutar flujo simulado». No hace falta pulsar antes el botón Análisis de la demostración anterior.
3. Desplazarse por la propuesta hasta «Borrador de informe editable». Deben verse observaciones, lecturas, daños, revisión, zonas y resumen.
4. Escribir «Informe revisado y aprobado por el operador» y ocultar el teclado.
5. Pulsar «Registrar edición» y desplazarse hasta el estado final: «SIMULADO · Revisión de edición confirmada.»
6. Comprobar también Aceptar y Rechazar. Las confirmaciones deben indicar aceptación o rechazo. Los botones son de ancho completo.

Las revisiones de esta demostración viven sólo en memoria. No se modifican formularios ni inspecciones, y el original de la propuesta conserva su texto. No se habilita ninguna prueba real de OpenAI con esta corrección.

## Verificación ejecutada

- **8 pruebas JVM específicas aprobadas**, 0 fallos/errores/omitidas: aceptación, edición con esquema completo, rechazo, UUID estables, contenido equivalente, conflictos y barrera de producción que impide toda invocación del transporte. Dobles en memoria sin HTTP.
- **5 casos de interfaz distintos aprobados en ANNXVB5708002966**, con el arnés existente y las pantallas reales de navegación/Conexión, restringidas a 300 × 480 dp. Se comprobaron todas las secciones y el desplazamiento hasta el último control, edición y confirmación, aceptar/rechazar, revisión repetida sin duplicados, excepción visible con reintento, timeout visible con desbloqueo, recreación conservando propuesta/informe/UUID y regreso al formulario conservando campos y referencia sin escrituras.
- Se ejecutaron 17 casos contando la preparación: 5 no pudieron empezar por la pantalla de bloqueo (Sergio desbloqueó físicamente); 1 detectó que el salto de página del arnés omitía una sección y se sustituyó por gestos táctiles terminados en reposo; 2 detectaron que la lectura de accesibilidad precedía al siguiente frame y se añadió espera por el mensaje visible. Sólo se repitieron las verificaciones afectadas. La última ejecución de cada uno de los cinco casos quedó aprobada. Estos fallos del arnés no se presentan como resultados aprobados.
- `assembleDebug`, `assembleDebugAndroidTest` y `lintDebug` correctos con JBR 21 y `--offline`. **Lint: 0 errores, 16 advertencias previas**. No se actualizaron dependencias.
- Instalación de APK principal y de pruebas mediante `adb install -r -t`: Success. Sin desinstalación ni limpieza de datos. APK debug SHA-256: `2d9da35c929ddf589dcc38cc2cce04552ed59fa7def9f4177fdaee5152350bf6`.
- Huellas SHA-256 de `infocontainer.db` y los **13 originales privados** idénticas antes y después. Las pruebas de UI usan repositorio en memoria y almacenamiento de fixture aislado; no guardan ni eliminan borradores de producción.
- Solicitudes HTTP de estas pruebas: **0**. Hostinger **0**, OpenAI **0**. El bloqueo real se mantiene en `false`. No se ejecutó Git ni se repitieron pruebas históricas de cámara, subida o recuperación.

Evidencias locales: `app/build/reports/ai-analysis-regression/verification-summary.json`, capturas de salida del runner y `device-before.txt` / `device-after.txt`; XML JVM bajo `app/build/test-results/testDebugUnitTest/` e informe `app/build/reports/lint-results-debug.html`. El procesamiento real del proveedor no está validado por estas simulaciones.
