# FOSScore — Biblioteca con metadatos (design)

Fecha: 2026-09-28. Estado: aprobado en conversación.

## Objetivo

Convertir la app de "visor de un PDF suelto" en una biblioteca local persistente de partituras, primera pieza del modelo de datos (Room). Los PDFs se importan una vez y se quedan dentro de la app.

## Decisiones (aprobadas por el usuario)

- **Importación**: copiar el PDF a `filesDir/sheets/<uuid>.pdf`. Sin referencias SAF.
- **Metadatos v1**: título (auto desde el nombre, editable), compositor, género, tonalidad, etiquetas.
- **Navegación**: composición condicional en MainActivity (Biblioteca → Detalle → Visor). Sin `navigation-compose`.
- **Última página**: se reutiliza `Settings.lastPage` (la uri de `filesDir` es estable). No se duplica en BD.

## Modelo de datos

Tabla `sheets` (Room, `@Entity`):

| columna | tipo | notas |
|---|---|---|
| id | Long | PK autogenerada |
| fileName | String | nombre original (solo display/fallback) |
| path | String | ruta absoluta en filesDir/sheets/ |
| hash | String | MD5 hex; detecta archivos duplicados al importar |
| title | String | inicial = fileName sin extensión; editable |
| composer | String | "" por defecto |
| genre | String | "" |
| musicalKey | String | "" |
| tags | String | texto libre, no se parsea en v1 |

`SheetDao`: `insert`, `update(sheet)`, `delete(id)`, `observeAll(): Flow<List<Sheet>>` orden ASC por title (colación simple, ponytail: sin collation custom).

## Capa de datos

- `FOSScoreApp : Application` (nuevo, registrado en Manifest): singleton manual de `AppDatabase` y `LibraryRepository`. Sin Hilt (YAGNI hasta que el grafo crezca).
- `library/SheetImporter.kt` (package `com.musigatto.fosscore.library`): función pura/testable que recibe `InputStream`, destino `File`, `fileName`, y calcula MD5 copiando en una pasada. Devuelve hash.
- `LibraryRepository.importSheet(uri)`: abre `contentResolver`, `SheetImporter` escribe en filesDir/sheets/uuid.pdf, consulta si el hash ya existe (duplicado → borra copia y devuelve error "ya en la biblioteca"), inserta, devuelve la entidad.
- `LibraryRepository.delete(id)`: borra fila y el archivo del disco.

## UI

- `MainActivity`: estado único `Screen` (`Library` / `Detail(sheetId)` / `Viewer(path)`), con `rememberSaveable` para sobrevivir rotación; composición condicional.
- `LibraryScreen`: top bar con botón "Importar" (picker SAF existente), LazyColumn de títulos/compositores, tap → Viewer, botón editar → Detail, borrar con diálogo de confirmación. Lista desde `repository.observeAll().collectAsState()`. Sin ViewModel en v1 (la lógica cabe en estado local; ViewModel llega con búsqueda).
- `SheetDetailScreen`: 5 `OutlinedTextField` + botón Guardar (persiste vía repo) + Eliminar (con confirm). Título autocompletado del fileName al insertar.
- Visor: sin cambios funcionales. Se abre con `Uri.fromFile(File(path))`. El mecanismo de `lastPage` sigue igual.

## Errores

- Importar duplicado → aviso "Ya está en la biblioteca".
- Archivo ilegible/copy fallido → aviso, copia parcial borrada.
- Borrar archivo que ya no existe en disco → se borra la fila igualmente (sin excepción).

## Dependencias nuevas

- plugin KSP `2.2.10-2.0.2` (matchea Kotlin 2.2.10), vía `libs.versions.toml`.
- `androidx.room:room-runtime:2.7.2`, `room-ktx:2.7.2`, `room-compiler:2.7.2` (ksp).
- `androidx.lifecycle:lifecycle-viewmodel-compose` — solo si al implementar hace falta (probablemente no en v1).

## Tests

- `SheetImporterTest` (JVM puro, temp files): (1) copia y hash correctos, (2) nombre/título derivado bien. Un archivo de test, sin frameworks extra.
- Se conservan `PageFlowTest` y `ThemeModeTest`. Verificación: `gradlew assembleDebug testDebugUnitTest`.

## Fuera de alcance (YAGNI hasta que se pida)

- Búsqueda/filtros (checklist propio), setlists, anotaciones, marcadores, pantalla de detalle con vista previa, edición de página inicial/lastPage en BD, export/backup, Hilt, ViewModel, migraciones (schema 1, `fallbackToDestructiveMigration`).