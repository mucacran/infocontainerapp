# InfoContainer (App Android)

Sistema de registro e inspección de contenedores para Mucacrán S.A.S.

## Información del Proyecto
- **Lenguaje:** Kotlin 2.2.10
- **Sistema de UI:** Jetpack Compose (Material 3)
- **Application ID:** `com.mucacran.infocontainer`
- **SDK Mínimo:** 26 (Android 8.0)
- **SDK Objetivo:** 36
- **Gradle Wrapper:** 9.4.1
- **Android Gradle Plugin (AGP):** 9.2.1

## Pasos para Compilación y Ejecución

### 1. Compilar Versión Debug
Desde la terminal en la raíz del proyecto:
```bash
./gradlew assembleDebug
```

### 2. Ejecutar en Dispositivo o Emulador
Con depuración USB activada y dispositivo conectado:
```bash
./gradlew installDebug
adb shell am start -n com.mucacran.infocontainer/.MainActivity
```
O directamente desde Android Studio haciendo clic en el botón **Run** (Ctrl + R / Shift + F10).

---

## Nota de Continuidad

### Estado Actual
- Proyecto base verificado en Kotlin con Jetpack Compose y Material 3.
- Pantalla inicial implementada con los elementos requeridos:
  - Nombre: **InfoContainer**
  - Subtítulo: **Registro e inspección de contenedores**
  - Identificación discreta: **Mucacrán S.A.S.**
  - Botones de acción: **Nueva inspección** e **Inspecciones guardadas**
  - Etiqueta visible: **Versión local de desarrollo**
- Pantallas provisionales navegables para cada botón con opción de retorno al inicio.

### Decisiones
- **Navegación Simple:** Manejo de estado ligero mediante `rememberSaveable` y `BackHandler` de Compose, sin incorporar librerías ni dependencias adicionales innecesarias.
- **Internacionalización:** Todos los textos definidos en `strings.xml`.
- **Seguridad:** Sin conexiones externas, credenciales ni claves integradas en el cliente.

### Verificaciones
- Compilación de la variante debug terminada de forma exitosa mediante `./gradlew assembleDebug`.
- Despliegue, instalación y ejecución confirmados en dispositivo físico autorizado conectado por ADB.
- Verificación visual real mediante inspección de UI en el teléfono, confirmando diseño y respuesta de botones.

### Pendientes
- Estructuración de datos y formulario para la captura de nueva inspección.
- Módulo de captura fotográfica y almacenamiento local.
- Conexión futura con API REST de WordPress.

### Siguiente Paso
- Definir los campos necesarios del formulario de "Nueva inspección" (ej. código de contenedor, tipo, observaciones) y construir la pantalla interactiva de captura.
