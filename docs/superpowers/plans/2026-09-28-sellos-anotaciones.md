# Sellos musicales + edición — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modo de edición que coloca, mueve y redimensiona sellos musicales sobre las páginas del visor, persistidos en Room como sidecar por hash.

**Architecture:** Sellos como entidad `Stamp` en Room v2 (migración desde v1), posicionados con coordenadas normalizadas (0..1) sobre el lienzo de la página; overlay Compose dentro del mismo `zoomMod` del visor mapea normalizado→px con una función pura `fitRect` (semántica `ContentScale.Fit`). La edición se hace en el mismo `pointerInput` de gestos actual (hit-test en el down, drag mueve, pinch sobre el sello redimensiona).

**Tech Stack:** Kotlin, Compose + Material 3, Room 2.7.2, JVM tests sin frameworks (JUnit4).

**Spec:** `docs/superpowers/specs/2026-09-28-sellos-anotaciones-design.md`

## Global Constraints

- Sin dependencias nuevas. Kotlin 2.2.10, Room 2.7.2, minSdk 26.
- Editar (y el overlay de sellos) SOLO en modo página única (`!twoUp && !(showHalf && halfTurned)`).
- Funciones de geometría puras en `StampGeometry.kt` SIN `android.graphics.*` (los stubs de android.jar lanzan "not mocked" en tests JVM). Usar `androidx.compose.ui.geometry.Offset` (ya probada en PageFlowTest) y data class propia.
- Copy en español. Sin commits: cada tarea termina en `git add` de sus archivos; el usuario commitea tras revisar.
- `sheetHash: String?` es opcional: PDF fuera de biblioteca → sin sellos, sin UI de edición, sin crash.

## Review Focus

| Input / condición | Comportamiento esperado | Se fija en |
|---|---|---|
| `sheetHash == null` (PDF abierto fuera de la biblioteca) | Sin overlay, sin paleta, toggle "Editar" inerte, sin crash | T4/T5 (guard en cada rama; compile + verificación) |
| Ranura degenerada (slot 0/negativo) o aspect ≤ 0 | `fitRect` devuelve rect vacío; `hitStamp` → null; nada crashea | T2 — test `fitRectZeroSlot` |
| Sello con x/y fuera de [0,1] en DB | Se clamp en `stampRect`, queda sobre el lienzo | T2 — test `outOfRangeStampClamped` |
| Segundo dedo sobre un sello (pinch) | Redimensiona el sello, NO el zoom de página | T5 — rama de gestos |

---

### Task 1: Persistencia — entidad, DAO, DB v2, repos

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/library/Stamp.kt`
- Create: `app/src/main/java/com/musigatto/fosscore/library/StampDao.kt`
- Create: `app/src/main/java/com/musigatto/fosscore/library/StampRepository.kt`
- Modify: `app/src/main/java/com/musigatto/fosscore/library/AppDatabase.kt`
- Modify: `app/src/main/java/com/musigatto/fosscore/library/LibraryRepository.kt`
- Modify: `app/src/main/java/com/musigatto/fosscore/FOSScoreApp.kt`

**Interfaces:**
- Produces (usan T4/T5):
  - `data class Stamp(id: Long = 0, sheetHash: String, page: Int, symbol: String, x: Float, y: Float, size: Float)` — esquema: `@Entity(tableName="stamps", indices=[Index("sheetHash","page")])`
  - `interface StampDao`: `observeBySheet(hash: String): Flow<List<Stamp>>` (ORDER BY page, id), `suspend insert(stamp): Long`, `suspend update(stamp)`, `suspend delete(stamp)`, `suspend deleteForSheet(hash: String)`
  - `class StampRepository(context: Context)`: `observe(hash): Flow<List<Stamp>>`, `suspend insert(stamp): Long`, `suspend update(stamp)`, `suspend delete(stamp)`
  - `FOSScoreApp.stampRepository: StampRepository`

- [ ] **Step 1: Crear `Stamp.kt`, `StampDao.kt`, `StampRepository.kt`** con las firmas de arriba (config generada por Room; sin lógica que testear).

- [ ] **Step 2: Subir `AppDatabase` a versión 2** — añadir `Stamp::class` a `entities`, `abstract fun stampDao(): StampDao`, y migración:

```kotlin
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stamps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sheetHash` TEXT NOT NULL, `page` INTEGER NOT NULL, `symbol` TEXT NOT NULL, " +
                "`x` REAL NOT NULL, `y` REAL NOT NULL, `size` REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stamps_sheetHash_page` ON `stamps` (`sheetHash`, `page`)")
    }
}
```

En `get(context)`: `.addMigrations(MIGRATION_1_2)` ANTES de `.fallbackToDestructiveMigration(dropAllTables = true)`. Quitar el comentario `ponytail: schema v1...` (ya no aplica).

- [ ] **Step 3: Cascada de borrado** en `LibraryRepository.delete(sheet)`: `private val stampDao = AppDatabase.get(context).stampDao()` y tras `dao.delete(sheet)` añadir `stampDao.deleteForSheet(sheet.hash)`.

- [ ] **Step 4: Exponer el repo** en `FOSScoreApp`: `val stampRepository: StampRepository by lazy { StampRepository(this) }`.

- [ ] **Step 5: Verificar build**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (KSP regenera; Room valida esquema/migración junto a `sheets`).

- [ ] **Step 6: Stage**

```bash
git add app/src/main/java/com/musigatto/fosscore/library/Stamp.kt app/src/main/java/com/musigatto/fosscore/library/StampDao.kt app/src/main/java/com/musigatto/fosscore/library/StampRepository.kt app/src/main/java/com/musigatto/fosscore/library/AppDatabase.kt app/src/main/java/com/musigatto/fosscore/library/LibraryRepository.kt app/src/main/java/com/musigatto/fosscore/FOSScoreApp.kt
```
Sin commit (el usuario commitea tras revisar).

---

### Task 2: Geometría del overlay — `StampGeometry` + tests (TDD)

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/ui/viewer/StampGeometry.kt`
- Create: `app/src/test/java/com/musigatto/fosscore/ui/viewer/StampGeometryTest.kt`

**Interfaces:**
- Consumes: `Stamp` (T1).
- Produces (usan T4/T5):
  - `data class RectPx(val left: Float, val top: Float, val width: Float, val height: Float)` con `right`/`bottom` y `contains(o: Offset): Boolean` (bounds inclusivos)
  - `const val MIN_STAMP_SIZE = 0.05f`, `MAX_STAMP_SIZE = 0.3f`, `DEFAULT_STAMP_SIZE = 0.09f`
  - `fun fitRect(slotW: Float, slotH: Float, aspect: Float): RectPx`
  - `fun normalizedToPx(n: Offset, fit: RectPx): Offset`
  - `fun pxToNormalized(p: Offset, fit: RectPx): Offset`
  - `fun stampRect(stamp: Stamp, fit: RectPx): RectPx` — x/y clamp [0,1]; size clamp [MIN,MAX] con `MAX_STAMP_SIZE`
  - `fun hitStamp(pos: Offset, stamps: List<Stamp>, fit: RectPx): Stamp?`

- [ ] **Step 1: Escribir el test que falla** en `StampGeometryTest.kt`

```kotlin
class StampGeometryTest {
    // retrato en ranura vertical (aspect > 1): se ajusta por ancho
    @Test fun landscapePageInPortraitSlotFitsByWidth()
    @Test fun portraitPageInLandscapeSlotFitsByHeight()
    @Test fun exactMatchFillsSlot()
    @Test fun zeroSlotReturnsEmptyAndNoHit()
    @Test fun normalizedToPxRoundtrips()   // (0.25,0.75) ida y vuelta, delta 1e-3
    @Test fun hitDetectsInsideAndMissesOutside()
    @Test fun outOfRangeStampClampedInside() // x=-0.2,y=1.3,size=0.9 → rect dentro del fit
}
```

Valores exactos:
- `fitRect(800f, 1000f, 1.5f)` → `RectPx(0f, 233.3333f, 800f, 533.3333f)` (delta 1e-3).
- `fitRect(1000f, 600f, 0.7f)` → `RectPx(290f, 0f, 420f, 600f)`.
- `fitRect(600f, 800f, 0.75f)` → `RectPx(0f, 0f, 600f, 800f)`.
- `fitRect(0f, 800f, 1.5f)` → `RectPx(0f, 0f, 0f, 0f)` y `hitStamp(Offset(1f,1f), listOf(stamp), empty)` → null.
- roundtrip: `pxToNormalized(normalizedToPx(Offset(.25f,.75f), fit), fit)` ≈ `(.25,.75)`.
- `stampRect` con stamp `x=-0.2f, y=1.3f, size=0.9f` sobre `fitRect(800,1000,1.5f)` → left≥0, top+height ≤ 800 (dentro del fit).
- hit: stamp `x=0.5f, y=0.5f, size=0.1f`, fit `Rectangle(0,0,1000,800)` → lado 80, centro (500,400): hit en (500,400), miss en (950,100).

- [ ] **Step 2: Verificar que falla**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:testDebugUnitTest --tests "com.musigatto.fosscore.ui.viewer.StampGeometryTest"`
Expected: compile error `Unresolved reference 'fitRect'` (o similar).

- [ ] **Step 3: Implementar `StampGeometry.kt`**

- `fitRect`: si `slotW <= 0f || slotH <= 0f || aspect <= 0f` → `RectPx(0,0,0,0)`; si no `width = min(slotW, slotH*aspect); height = width/aspect` centrado.
- `normalizedToPx` / `pxToNormalized` como inversas lineales sobre el rect.
- `stampRect`: clampa `x/y/size` en `[0,1]`/`[MIN,MAX]` y devuelve `RectPx(fit.left + x*fit.width, fit.top + y*fit.height, side, side)` con `side = size*fit.height`.

- [ ] **Step 4: Verificar que pasa**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:testDebugUnitTest --tests "com.musigatto.fosscore.ui.viewer.StampGeometryTest"`
Expected: PASS todos.

- [ ] **Step 5: Stage**

```bash
git add app/src/main/java/com/musigatto/fosscore/ui/viewer/StampGeometry.kt app/src/test/java/com/musigatto/fosscore/ui/viewer/StampGeometryTest.kt
```

---

### Task 3: Símbolos y render — `StampSymbol` + `StampView` + test (TDD)

**Files:**
- Create: `app/src/main/java/com/musigatto/fosscore/ui/viewer/StampView.kt`
- Create: `app/src/test/java/com/musigatto/fosscore/ui/viewer/StampSymbolTest.kt`

**Interfaces:**
- Consumes: nada nuevo (T1/T2 no usan esto).
- Produces (usan T4/T5):
  - `enum class StampSymbol(val label: String)` — exactamente 14 entradas, en este orden:
    `PP("pp"), P("p"), MP("mp"), MF("mf"), F("f"), FF("ff"), CRESC("cresc."), DECRESC("decresc."), UPBOW("upbow"), DOWNBOW("downbow"), ACCENT("acento"), STACCATO("staccato"), TENUTO("tenuto"), FERMATA("fermata")`
  - `fun baseFontSize(sizePx: Float): TextUnit` (≈ `sizePx * 0.6f` en sp)
  - `@Composable fun StampView(symbol: StampSymbol, sizePx: Float, color: Color, modifier: Modifier = Modifier)`
  - `@Composable fun StampPaletteButton(symbol: StampSymbol, selected: Boolean, onClick: () -> Unit)` — preview mini (~24.dp) con borde de selección

- [ ] **Step 1: Escribir el test que falla** en `StampSymbolTest.kt`

```kotlin
class StampSymbolTest {
    @Test fun completeSetOf14WithUniqueLabels() {
        assertEquals(14, StampSymbol.entries.size)
        assertEquals(StampSymbol.entries.size, StampSymbol.entries.map { it.label }.toSet().size)
        StampSymbol.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
```

- [ ] **Step 2: Verificar que falla**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:testDebugUnitTest --tests "com.musigatto.fosscore.ui.viewer.StampSymbolTest"`
Expected: compile error `Unresolved reference 'StampSymbol'`.

- [ ] **Step 3: Implementar**

`StampView`: para `PP..FF` un `Text(symbol.label, fontSize = baseFontSize(sizePx), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)`. El resto, un `Canvas(Modifier.size(sizeDp, sizeDp))` que dibuja en el rango 0..sizePx:
- `CRESC`: hairpin abriendo a la derecha (`path.moveTo(0,h) → lineTo(w,h/2) → lineTo(0,0)`)
- `DECRESC`: hairpin abriendo a la izquierda (espejo)
- `UPBOW`: polyline en "V" con gancho bajo
- `DOWNBOW`: polyline en "Λ" (squiggly corta)
- `ACCENT`: `>` horizontal centrado
- `STACCATO`: punto relleno centrado (radio `sizePx*0.12`)
- `TENUTO`: línea gruesa horizontal centrada (alto `sizePx*0.15`)
- `FERMATA`: medio arco abajo + punto en el centro (vista desde arriba; ambas orientaciones sirven)
Stroke `sizePx*0.08f`, `StrokeCap.Round`. Persiste el `symbol.name` como string en DB (T1).

`StampPaletteButton`: `Box` de 28.dp con borde `primary` si `selected` y `StampView` de 24.dp centrado; `Modifier.clickable(onClick = onClick)`.

- [ ] **Step 4: Verificar que pasa**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:testDebugUnitTest --tests "com.musigatto.fosscore.ui.viewer.StampSymbolTest"`
Expected: PASS.

- [ ] **Step 5: Verificar compilación completa**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Stage**

```bash
git add app/src/main/java/com/musigatto/fosscore/ui/viewer/StampView.kt app/src/test/java/com/musigatto/fosscore/ui/viewer/StampSymbolTest.kt
```

---

### Task 4: Overlay de sellos en el visor + `sheetHash` desde la biblioteca

**Files:**
- Modify: `app/src/main/java/com/musigatto/fosscore/ui/viewer/PdfViewerScreen.kt`
- Modify: `app/src/main/java/com/musigatto/fosscore/MainActivity.kt`

**Interfaces:**
- Consumes: `StampRepository` (T1), `StampGeometry` (T2), `StampView`/`StampSymbol` (T3), `FOSScoreApp`.
- Produces (usa T5):
  - `fun PdfViewerScreen(pdfUri: Uri, onBack: () -> Unit, themeMode: ThemeMode, onToggleTheme: () -> Unit, sheetHash: String? = null)`
  - estado `stamps: List<Stamp>` (colección por hash, solo si `sheetHash != null`)

- [ ] **Step 1: Añadir el parámetro `sheetHash: String? = null`** y coleccionar los sellos

```kotlin
val stampsFlow = remember(sheetHash) {
    sheetHash?.let { (LocalContext.current.applicationContext as FOSScoreApp).stampRepository.observe(it) }
        ?: flowOf(emptyList())
}
val stamps by stampsFlow.collectAsState(initial = emptyList())
```

- [ ] **Step 2: Renderizar el overlay en la rama de página única**

En la rama `else ->` (página única), envolver la `Image` en un `Box(modifier = zoomMod)` y añadir debajo, cuando `sheetHash != null && !twoUp && !(showHalf && halfTurned)`:

```kotlin
val pageStamps = stamps.filter { it.page == currentPage }
val fit = remember(bitmaps[currentPage], viewport) {
    fitRect(viewport.width.toFloat(), viewport.height.toFloat(),
        bitmaps[currentPage]!!.width.toFloat() / bitmaps[currentPage]!!.height.toFloat())
}
pageStamps.forEach { stamp ->
    val sym = runCatching { StampSymbol.valueOf(stamp.symbol) }.getOrNull() ?: return@forEach
    val r = stampRect(stamp, fit)
    StampView(
        symbol = sym,
        sizePx = r.width,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(with(LocalDensity.current) { r.width.toDp() })
    )
}
```

Imperiosas: overlay DENTRO del `Box(zoomMod)` (mismo corte de zoom), `offset` con `IntOffset`, y el sello no consume gestos (los gestos siguen en `zoomMod.pointerInput`). `ponytail: sellos solo en página única; two-up/half-turn no los dibujan (el half‑combine no tiene dónde mapearlos), upgrade: fitRect por mitad de ranura cuando el modo edición lo pida.`

Nota: hoy `zoomMod` se aplica a la propia `Image`. Moverlo al `Box` no cambia la geometría (mismo slot `fillMaxSize`); verificar con el build.

- [ ] **Step 3: Pasar el hash desde `MainActivity`**

En `Screen.Viewer`: `PdfViewerScreen(..., sheetHash = s.sheet.hash)` (la 4ª línea ya existe en la llamada actual).

- [ ] **Step 4: Verificar**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; tests verdes (14 + 8 nuevos). Sin `sheetHash` no cambia el comportamiento actual (guard de Review Focus #1).

- [ ] **Step 5: Stage**

```bash
git add app/src/main/java/com/musigatto/fosscore/ui/viewer/PdfViewerScreen.kt app/src/main/java/com/musigatto/fosscore/MainActivity.kt
```

---

### Task 5: Modo edición — paleta, colocar, mover, redimensionar

**Files:**
- Modify: `app/src/main/java/com/musigatto/fosscore/ui/viewer/PdfViewerScreen.kt`

**Interfaces:**
- Consumes: T2 (`pxToNormalized`, `hitStamp`, `fitRect`, `MIN/MAX/DEFAULT_STAMP_SIZE`), T3 (`StampPaletteButton`), T1 (`stampRepository`, `Stamp`).
- Produces: nada nuevo (cierra la integración).

- [ ] **Step 1: Estado de edición**

```kotlin
var editing by rememberSaveable { mutableStateOf(false) }
var activeSymbol by rememberSaveable { mutableStateOf<StampSymbol?>(null) }
var dragStamp by remember { mutableStateOf<Stamp?>(null) }   // el que se mueve/redimensiona en vivo
val scope = rememberCoroutineScope()
```

`val canEdit = sheetHash != null && !twoUp && !(showHalf && halfTurned)` — reusar para activar toggle y paleta. Un helper `val pageFit = fitRect(viewport..., aspect...)` reutilizado por overlay y gestos (extraer a una variable `remember` compartida en el espectro de composición de la rama de página única; si no encaja, recalcular en ambos sitios — idéntico resultado).

- [ ] **Step 2: Toggle "Editar" en la barra superior**

Botón `Text("Editar")` al final de la `Row` superior, colores `buttonColors` si `editing` si no `outlinedButtonColors`, `enabled = canEdit`. Al activar: `editing = true; twoUp = false; halfEnabled = false; halfTurned = false`.

Paleta: cuando `editing && canEdit`, en la `Column` inferior, entre la fila de dim y la fila de navegación, un `LazyRow` con `StampPaletteButton` por cada `StampSymbol.entry`; `selected = (it == activeSymbol)`, `onClick = { activeSymbol = it }`.

- [ ] **Step 3: Rama de gestos de edición** dentro del `awaitEachGesture` existente (tras el `awaitFirstDown`)

Estado por gesto declarado tras el down:

```
val hit = if (editing && canEdit) hitStamp(down.position, pageStampsLatest, pageFit) else null
dos var: movingStamp = hit; resizeAnchor = 0f; resizeBase = if (hit) hit.size else 0f
```
`pageStampsLatest` vía `rememberUpdatedState(stamps)` o una lista filtrada por página recomputada con `remember(stamps, currentPage)`.

Dentro del bucle `while`:
- **`movingStamp != null`:**
  - 1 dedo: `m = pxToNormalized(c, pageFit) - pxToNormalized(prevCentroid, pageFit)`; `movingStamp = movingStamp.copy(x = (movingStamp.x + m.x).coerceIn(0f,1f), y = (movingStamp.y + m.y).coerceIn(0f,1f))`; `dragStamp = movingStamp`. (El overlay de T4 dibuja `dragStamp` en lugar del sello con el mismo `id` cuando no es null.)
  - 2 dedos (cambio de `ids`): setear `resizeAnchor = d` y `resizeBase = movingStamp.size`; en cada evento con 2 dedos: `movingStamp = movingStamp.copy(size = (resizeBase * d / resizeAnchor).coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE))`.
  - NO tocar `scale`/`offset` en esta rama (el pinch redimensiona el sello, no la página — Review Focus #4). Consumir cambios.
- **`movingStamp == null`** (tocó vacío): comportamiento actual intacto pero con tap zones y swipe desactivados si `editing && canEdit`:
  - `scale == 1f` y release sin movimiento → colocar: `pos = pxToNormalized(down.position, pageFit)`; si `activeSymbol != null`: `scope.launch { stampRepository.insert(Stamp(sheetHash = sheetHash!!, page = currentPage, symbol = activeSymbol!!.name, x = pos.x.coerceIn(0f,1f), y = pos.y.coerceIn(0f,1f), size = DEFAULT_STAMP_SIZE)) }`.
  - `scale > 1f` → pan normal (sin tap-zones).

Al terminar el gesto: si `movingStamp != null` → `scope.launch { stampRepository.update(movingStamp) }`; `dragStamp = null`.

- [ ] **Step 4: `dragStamp` en el overlay**

En el bloque de render de T4, sustituir el sello con `id == dragStamp.id` por `dragStamp` (para ver el drag en vivo), dibujado igual. El toque sobre un sello ya colocado también lo selecciona en la paleta: setear `activeSymbol = StampSymbol.valueOf(stamp.symbol)` en el hit (comodidad de "repetir herramienta").

- [ ] **Step 5: Verificar**

Run: `& "C:\Users\darlb\Desktop\FOSScore\gradlew.bat" :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; suite completa verde (14 preexistentes + `SheetImporterTest` 2 + `ThemeModeTest` 2 + `PageFlowTest` 6 + `SheetFilterTest` 4 + `StampGeometryTest` 7 + `StampSymbolTest` 1).

- [ ] **Step 6: Stage**

```bash
git add app/src/main/java/com/musigatto/fosscore/ui/viewer/PdfViewerScreen.kt
```

---

## Non-goals (explicitamos para no crecer)

- Borrar sellos (colocados mal): fuera de v1; el hit-test ya lo habilita, se añade un botón en la paleta cuando se pida.
- Edición/render en two-up y half-turn, multi-color, dibujo a mano, texto libre, capas: YAGNI hasta que se pidan (spec).