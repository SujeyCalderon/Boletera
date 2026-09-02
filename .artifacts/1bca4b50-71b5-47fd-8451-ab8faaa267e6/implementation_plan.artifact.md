# Plan de Migración de Funcionalidad: de MOBI a boletera

Este plan detalla la migración de la lógica funcional del proyecto MOBI al proyecto boletera, manteniendo la arquitectura limpia y convirtiendo el código a Kotlin.

## User Review Required

> [!IMPORTANT]
> El código se convertirá de Java a Kotlin para alinearse con la estructura actual del proyecto `boletera`, manteniendo la lógica de negocio idéntica a la del proyecto `MOBI`.

## Proposed Changes

### [Component Name] Data Layer (Remote)

#### [NEW] [ApiClient.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/data/remote/ApiClient.kt)
Configuración de Retrofit con la URL base y el convertidor Gson.

#### [NEW] [ApiService.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/data/remote/ApiService.kt)
Interfaz con todos los end-points de la API.

#### [NEW] [DTOs (Request/Response)](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/data/remote/model/)
- `LoginRequest.kt`, `LoginResponse.kt`
- `TarifasRequest.kt`, `TarifasResponse.kt`
- `PartialCutRequest.kt`
- `TransactionSyncRequest.kt`, `TransactionSyncResponse.kt`

---

### [Component Name] Data Layer (Local)

#### [NEW] [DatabaseHelper.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/data/local/DatabaseHelper.kt)
Gestión de usuarios y autenticación offline.

#### [NEW] [ControlCortes.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/data/local/ControlCortes.kt)
Base de datos principal para ventas, cortes y logs.

#### [NEW] [UsbPrinterManager.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/data/local/UsbPrinterManager.kt)
Manejo de conexión y permisos de hardware USB.

---

### [Component Name] Domain Layer

#### [NEW] [Models](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/domain/model/)
- `CorteTotal.kt`: Modelo para la UI de cortes.
- `SaleItem.kt`: Modelo de ítem de venta.
- `TarifaControl.kt`: Lógica de ordenamiento y selección de tarifas.

---

### [Component Name] Common / Utils

#### [NEW] [ImpresoraController.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/common/ImpresoraController.kt)
Controlador singleton de impresión con lógica de timeout.

#### [NEW] [Utils.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/common/Utils.kt)
Utilidades de UI, Sonido, Formateo y Diálogos de Respaldo.

#### [NEW] [CrashHandler.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/common/CrashHandler.kt)
Captura de excepciones no controladas.

#### [NEW] [LogUploader.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/common/LogUploader.kt)
Sincronización de logs de error con el servidor.

---

### [Component Name] Presentation Layer

#### [NEW] [Activities](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/presentation/)
- `MainActivity.kt`: Login y flujo inicial.
- `CobroActivity.kt`: Interfaz principal de ventas.
- `CortesActivity.kt`: Gestión de cierres de caja.
- `BluetoothActivity.kt`: Configuración de periféricos.
- `PrecargaActivity.kt`: Splash screen.
- `CrearActivity.kt`: Registro de usuarios.
- `RegistroActivity.kt`: Pantalla de soporte/registro.

#### [NEW] [CorteAdapter.kt](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/java/mx/com/rutamovil/boletera/presentation/adapter/CorteAdapter.kt)
Adaptador para la lista de cortes y ventas.

---

### [Component Name] System Configuration

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/taqui/AndroidStudioProjects/boletera/app/src/main/AndroidManifest.xml)
Configuración de permisos (GPS, BT, USB), Temas y LaunchModes de las actividades.

## Verification Plan

### Automated Tests
- Verificación de compilación exitosa tras la migración.
- Pruebas de persistencia (SQLite) para asegurar que los datos se guardan correctamente.

### Manual Verification
- Pruebas de Login (Online/Offline).
- Simulación de ventas y verificación de impresión de tickets.
- Ejecución de cortes parciales y totales.
- Verificación de sincronización con la API.
