# Biblioteca con metadatos — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convertir la app en una biblioteca local persistente: importar PDFs a memoria interna, listarlos, editar 5 metadatos y abrirlos en el visor.

**Architecture:** Room con una sola entidad `sheets` (KSP), repository singleton manual en una clase `Application`, pantallas Biblioteca/Detalle/Visor conmutadas por composición condicional desde MainActivity. `SheetImporter` puro y testable en JVM.

**Tech Stack:** Kotlin, Compose + Material3, Room 2.7.2 (KSP `2.2.10-2.0.2`), JUnit.

**Spec:** `docs/superpowers/specs/2026-09-28-biblioteca-metadatos-design.md`

## Global Constraints

- Packages: `com.musigatto.fosscore.library` (data) y `com.musigatto.fosscore.ui.library` (UI).
- Room `2.7.2`, KSP `2.2.10-2.0.2`; sin Hilt, sin `navigation-compose`, sin ViewModel (v1).
- `filesDir/sheets/<uuid>.pdf` es la única ubicación de almacenamiento.
- `lastPage` se reutiliza de `Settings` (no hay columna en BD).
- Textos de UI en español (botones) e inglés en contenido de debug; nombres en inglés en código.
- Nada de telemetría, cuentas, cloud, plugins.

## Review Focus

1. Importar el mismo PDF dos veces → segundo intento rechazado con "Ya está en la biblioteca", sin dejar copia huérfana en disco.
2. Stream ilegible/archivo roto al importar → error visible y copia parcial borrada; la app no crashea.
3. Borrar uno de la lista con su archivo ausente en disco → la fila se borra sin excepción.
4. Rotación de pantalla en Biblioteca/Visor → el estado (pantalla activa, texto de detalle) sobrevive. `rememberSaveable`.
5. El visor se abre desde la biblioteca → PDF multivista (two-up/half) y última página funcionan igual que antes (regresión).

---

### Task 1: KSP + Room en el build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: nada (infraestructura).
- Produces: plugin KSP aplicado (`ksp(libs.androidx.room.compiler)` usable en Task 2), alias `libs.androidx.room.runtime`, `libs.androidx.room.compiler`.

- [ ] **Step 1: Añadir catálogo de versiones KSP y Room**

En `gradle/libs.versions.toml`: versión `ksp = "2.2.10-2.0.2"`, plugin `ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }`; `room = "2.7.2"`, librerías `androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }`, `room-ktx`, `room-compiler` (mismo grupo/versión).

- [ ] **Step 2: Aplicar KSP y dependencias en `app/build.gradle.kts`**

`alias(libs.plugins.ksp)` en plugins; en dependencies: `implementation(libs.androidx.room.runtime)`, `implementation(libs.androidx.room.ktx)`, `ksp(libs.androidx.room.compiler)`.

- [ ] **Step 3: Verificar que el build resuelve**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (gradle descarga Room/KSP; no hay código Room aún).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Room 2.7.2 and KSP 2.2.10-2.0.2"
```

---

### Task 2: Entidad, DAO y base de datos

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/library/Sheet.kt`
- Create: `app/src/main/java/com/musigatto/fosscore/library/SheetDao.kt`
- Create: `app/src/main/java/com/musigatto/fosscore/library/AppDatabase.kt`

**Interfaces:**
- Consumes: nada (Task 1 solo build).
- Produces:
  - `data class Sheet(id: Long, fileName: String, path: String, hash: String, title: String, composer: String, genre: String, musicalKey: String, tags: String)`
  - `@Dao interface SheetDao { suspend fun insert(s: Sheet): Long; suspend fun update(s: Sheet); suspend fun delete(id: Long); suspend fun byHash(hash: String): Sheet?; fun observeAll(): Flow<List<Sheet>> }`
  - `@Database(entities = [Sheet::class], version = 1) abstract class AppDatabase : RoomDatabase { abstract fun sheetDao(): SheetDao; companion object { fun get(context: Context): AppDatabase } }` — `get` construye con `Room.databaseBuilder(...).fallbackToDestructiveMigration().build()` y cachea en un field.

- [ ] **Step 1: Definir entidad `Sheet`** en `library/Sheet.kt` con los tipos exactos de Interfaces y `@Entity(tableName = "sheets")`; `id` Long autoGenerate la PK.

- [ ] **Step 2: Definir `SheetDao`** con las firmas exactas de Interfaces; `observeAll` con `@Query("SELECT * FROM sheets ORDER BY title ASC, id ASC")` y retorno `Flow<List<Sheet>>`; `byHash` con `@Query("SELECT * FROM sheets WHERE hash = :hash LIMIT 1")`.

- [ ] **Step 3: Definir `AppDatabase`** (firmas exactas), `companion object { fun get(context: Context): AppDatabase }` construyendo con `Room.databaseBuilder(context, AppDatabase::class.java, "fosscore.db").fallbackToDestructiveMigration().build()` y cachea en un field (schema v1, ponytail: migraciones cuando existan).

- [ ] **Step 4: Verificar compila**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (KSP genera la implementación).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/musigatto/fosscore/library/
git commit -m "feat: add Sheet entity, DAO and Room database"
```

---

### Task 3: `SheetImporter` puro + test JVM

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/library/SheetImporter.kt`
- Test: `app/src/test/java/com/musigatto/fosscore/library/SheetImporterTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `object SheetImporter { fun import(input: InputStream, destination: File): String }` — copia bytes y devuelve el hash MD5 hex; lanza `IOException` si falla a mitad (deja destino a medio escribir, el repo lo borra).

- [ ] **Step 1: Write the failing test** `SheetImporterTest.kt` con dos asserts:

```kotlin
@Test fun copiesBytesAndReturnsMd5() {
    val src = File.createTempFile("src", ".pdf"); src.writeBytes(byteArrayOf(1,2,3,5,8,13))
    val dst = File.createTempFile("dst", ".pdf")
    val hash = SheetImporter.import(src.inputStream(), dst)
    assertEquals(MessageDigest.getInstance("MD5").digest(src.readBytes()).toHex(), hash)
    assertArrayEquals(src.readBytes(), dst.readBytes())
}
@Test fun derivesTitleFromFileName() { assertEquals("Preludio 3", SheetImporter.titleFrom("Preludio 3.pdf")) }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.musigatto.fosscore.library.SheetImporterTest"`
Expected: FAIL (no compila `SheetImporter`).

- [ ] **Step 3: Implement `SheetImporter`**

`import`: bucle de lectura 64 KB con `MD5Digest` y escritura en destination, `finally { input.close() }`; `titleFrom(name)` = `name.substringBeforeLast('.').trim()`. Añadir `toHex()` privada.

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.musigatto.fosscore.library.SheetImporterTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/musigatto/fosscore/library/SheetImporter.kt app/src/test/java/com/musigatto/fosscore/library/SheetImporterTest.kt
git commit -m "feat: add SheetImporter with MD5 and JVM tests"
```

---

### Task 4: `FOSScoreApp` + `LibraryRepository`

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/FOSScoreApp.kt`
- Modify: `app/src/main/AndroidManifest.xml` (registrar `android:name=".FOSScoreApp"`)
- Create: `app/src/main/java/com/musigatto/fosscore/library/LibraryRepository.kt`

**Interfaces:**
- Consumes: `Sheet`, `SheetDao`, `AppDatabase` (Task 2), `SheetImporter` (Task 3).
- Produces:
  - `class LibraryRepository(context: Context)` con:
    - `fun sheets(): Flow<List<Sheet>>` = dao.observeAll()
    - `suspend fun importSheet(uri: Uri): ImportResult` donde `sealed class ImportResult { data class Ok(val sheet: Sheet): ImportResult; data class Duplicate(val existing: Sheet): ImportResult; data class Error(val reason: String): ImportResult }`
    - `suspend fun save(sheet: Sheet)` (update)
    - `suspend fun delete(sheet: Sheet)` (borra fila y archivo, sin excepción si el archivo no existe)

- [ ] **Step 1: Crear `FOSScoreApp`** con `val db: AppDatabase by lazy { AppDatabase.get(this) }` y `val repository: LibraryRepository by lazy { LibraryRepository(this) }`; registrarlo en el Manifest.

- [ ] **Step 2: Implementar `importSheet`**

Abrir `contentResolver.openInputStream(uri)`, destino `File(filesDir, "sheets/<uuid>.pdf")` (mkdirs padre), `SheetImporter.import`; si `SheetImporter` lanza IOException → `Error(...)` y borrar destino. `dao.byHash(hash)` → si existe `Duplicate`. Insertar y devolver `Ok`.

- [ ] **Step 3: Implementar `delete`**

`dao.delete(sheet.id)` y `File(sheet.path).delete()` con try/catch ignorado.

- [ ] **Step 4: Verificar compila y correr tests**

Run: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, todos los tests (incl. 2 de SheetImporterTest).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/musigatto/fosscore/FOSScoreApp.kt app/src/main/AndroidManifest.xml app/src/main/java/com/musigatto/fosscore/library/
git commit -m "feat: add LibraryRepository with import, duplicate detection and delete"
```

---

### Task 5: `LibraryScreen` y `SheetDetailScreen`

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/ui/library/LibraryScreen.kt`
- Create: `app/src/main/java/com/musigatto/fosscore/ui/library/SheetDetailScreen.kt`

**Interfaces:**
- Consumes: `LibraryRepository`, `Sheet`, `ImportResult` (Task 4).
- Produces (firmas usadas por MainActivity en Task 6):
  - `@Composable fun LibraryScreen(repository: LibraryRepository, onOpen: (Sheet) -> Unit, onEdit: (Sheet) -> Unit, onImport: () -> Unit)`
  - `@Composable fun SheetDetailScreen(repository: LibraryRepository, sheet: Sheet, onDone: () -> Unit)`

- [ ] **Step 1: Implementar `LibraryScreen`**

Top bar con título "Biblioteca" y botón "Importar" que dispara `onImport` (el picker SAF vive en MainActivity y llama `onImport`). LazyColumn de `repository.sheets().collectAsState(initial = emptyList())` mostrando title + composer; tap → onOpen; botón editar → onEdit. Estado vacío: "Aún no hay partituras. Importa una con el botón Importar."

- [ ] **Step 2: Implementar `SheetDetailScreen`**

5 `OutlinedTextField` (title, composer, genre, musicalKey, tags) en un `rememberSaveable` por cada campo, inicializados del `sheet`; botones **Guardar** (copia del `sheet` con campos editados → `repository.save`) y **Eliminar** (confirmación `AlertDialog` → `repository.delete` → onDone) y atrás → onDone.

- [ ] **Step 3: Verificar compila**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/musigatto/fosscore/ui/library/
git commit -m "feat: add library list and sheet detail screens"
```

---

### Task 6: MainActivity — navegación condicional y flujo de import

**Files:**
- Modify: `app/src/main/java/com/musigatto/fosscore/MainActivity.kt` (reescritura de `setContent`)
- Referencia: `PdfViewerScreen` (sin cambios de visor)

**Interfaces:**
- Consumes: `FOSScoreApp.repository`, `LibraryScreen`, `SheetDetailScreen` (Task 5), `ImportResult` (Task 4).

- [ ] **Step 1: Reescribir `setContent`**

Estado `Screen`: `sealed interface Screen { object Library; data class Detail(val sheet: Sheet); data class Viewer(val path: String) }` (recordable), `rememberSaveable` para la pantalla activa (guardar sheet como JSON simple de campos o `String` path + id). Reemplazar el bloque picker actual: `LibraryScreen(repository, onOpen = { Screen.Viewer(it.path) }, onEdit = { Screen.Detail(it) }, onImport = lanzar el picker SAF existente)`.

- [ ] **Step 2: Implementar el flujo de import**

Callback del picker: `viewModelScope` no; usar `rememberCoroutineScope()`. `repository.importSheet` en corutina; resultado → `snackbar`/toast con "Importada" o "Ya está en la biblioteca"/error (texto del `ImportResult`). El tema/dark/themeMode del visor se conserva (param `themeMode`/`onToggleTheme` igual que hoy).

- [ ] **Step 3: Visor desde biblioteca**

`Screen.Viewer(path)` → `PdfViewerScreen(pdfUri = Uri.fromFile(File(path)), onBack = volver a Library, themeMode, onToggleTheme)` con las mismas params actuales. `Settings.lastPage` ya restaura por uri estable.

- [ ] **Step 4: Verificación completa**

Run: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, todos los tests en verde (PageFlowTest, ThemeModeTest, SheetImporterTest).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/musigatto/fosscore/MainActivity.kt
git commit -m "feat: wire library, detail and viewer navigation with import flow"
```

---

### Task 7: Actualizar AGENTS.md (checklist)

**Files:**
- Modify: `AGENTS.md` (gitignored — tachado local, no commit)

- [ ] **Step 1: Marcar el checklist**

`[x] Biblioteca con metadatos (compositor, género, tonalidad, etiquetas, etc.)` y `[x] Búsqueda y filtros dinámicos` **no** — solo marcar Biblioteca; añadir nota "importación a memoria interna, duplicados por MD5". No commitear (gitignored).

- [ ] **Step 2: Verificación final**

Run: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: verde completo.