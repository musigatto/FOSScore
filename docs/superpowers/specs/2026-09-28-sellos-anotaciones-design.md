# Sellos musicales + edición de partitura — Diseño

Fecha: 2026-09-28
Estado: aprobado por diseño (colocar + mover + redimensionar, set completo de 14 símbolos)

## Objetivo

Modo de edición en el visor PDF que permite colocar, mover y redimensionar sellos
musicales (dinámicas y agógicas) sobre las páginas, persistiéndolos en SQLite como
sidecar vinculado al hash de la partitura, tal como manda AGENTS.md.

## Contexto

- El visor (`PdfViewerScreen.kt`) renderiza páginas como `Image` con `ContentScale.Fit`
  dentro de un `Box` con `zoomMod` (`graphicsLayer` scale/translation). El zoom/pan y los
  gestos (tap zones, swipe, pinch) viven en un único `pointerInput`.
- La biblioteca ya tiene Room v1 (`fosscore.db`, tabla `sheets` con `hash`), `FOSScoreApp`
  con `database`/`repository`, y navegación Library → Detail → Viewer en `MainActivity`.
- El catálogo se renderiza con `colorScheme.primary` (único color, visible en claro y oscuro).

## Alcance (v1)

- **Símbolos (14).** Dinámicas: `pp, p, mp, mf, f, ff` (texto serif itálica).
  Crescendo `<` y decrescendo `>` (hairpin dibujado). Articulación: `upbow, downbow,
  accento, staccato, tenuto, fermata` (paths en Canvas).
- **Edición:** colocar (tap en vacío), mover (drag sobre un sello), redimensionar
  (segundo dedo → pinch, clamp del tamaño). Persistencia inmediata al soltar.
- **Editar se usa solo en modo página única** (apaga two-up y half-turn).
- Navegación al tocar y swipe desactivados en modo editar (girar = botones ◀ ▶).

## No va en v1 (YAGNI)

- Dibujo a mano alzada, texto libre, capas — items separados del checklist Anotaciones.
- Multi-color por anotación, import/export de anotaciones.
- Edición en two-up / half-turn (ceiling documentado en la geometría de overlay).

## Arquitectura

### 1. Persistencia — Room v1→v2

`Stamp` (nueva tabla):

```
@Entity(tableName = "stamps", indices = [Index("sheetHash", "page")])
data class Stamp(
  id: Long (PK autoGenerate),
  sheetHash: String,
  page: Int,            // índice de página 0-based
  symbol: String,       // nombre de StampSymbol
  x: Float, y: Float,   // normalizado 0..1 sobre el lienzo de la página
  size: Float           // fracción de la altura de página, clamp 0.05..0.3
)
```

- `Migration(1,2): CREATE TABLE stamps ...` + `addMigrations`. Se conserva
  `fallbackToDestructiveMigration(dropAllTables = true)` para v3+.
- `StampDao`: `observeBySheet(hash): Flow<List<Stamp>>`, `insert`, `update`, `delete`,
  `deleteForSheet(hash)`.
- `StampRepository(context)`: igual DI manual que `LibraryRepository`; expone
  `observe(hash)`, `insert`, `update`, `delete`.
- `LibraryRepository.delete(sheet)` encadena `stampDao.deleteForSheet(sheet.hash)`.
- `FOSScoreApp` expone `stampRepository`.
- `PdfViewerScreen` recibe `sheetHash: String?` (null → sin anotaciones; se preserva el
  camino de abrir un PDF fuera de la biblioteca).

### 2. Geometría — overlay (lógica pura, testeable)

`StampGeometry.kt` (funciones puras, JVM-testable):

- `fitRect(slotW, slotH, aspect): RectF` — rectanglo real que ocupa el PDF renderizado
  dentro de su ranura con `ContentScale.Fit` (aspect = bmpW/bmpH). Centrado.
- `normalizedToPx(n, fit): Offset` y `pxToNormalized(px, fit): Offset` — inversas.
- `hitTest(stamp, posPx, fit, pageH): Boolean` — el sello vive en `(x*fit.w + fit.left,
  y*fit.h + fit.top, size*fit.h)`; punto dentro del rect → hit.

Los sellos se renderizan DENTRO del mismo `zoomMod` (mismo `graphicsLayer`), por lo que
zoom/pan los escala sin duplicar el mapa de coordenadas.

### 3. Render de sellos

- `StampView(symbol, sizePx, color)`: `Text` serif itálica para dinámicas
  (pp/p/mp/mf/f/ff), `Canvas` con paths para hairpins (cresc `<`, decresc `>`) y
  articulaciones (upbow, downbow, acento, staccato, tenuto, fermata). Los glyphs Unicode
  de música no están garantizados en la fuente del sistema → se dibujan.
- `StampSymbol` es un `enum` con los 14, `symbolId` único = nombre, `label` para la paleta.
- Paleta: `LazyRow` bajo la barra inferior cuando el modo editar está activo; botón por
  símbolo (mini `StampView`), resaltado el activo.

### 4. Edición — gestos

Extiende el `pointerInput` existente con rama de edición (estado vía `rememberUpdatedState`):

- `editing: Boolean` (toggle "Editar" en barra superior). Al activarlo: `twoUp = false`,
  `halfEnabled = false`. Desactiva tap zones y swipe.
- En `awaitFirstDown`: hacer `hitTest` sobre los sellos de la página actual.
  - **Hit → sello:** drag mueve (delta px→normalizado vía `pxToNormalized`, update en
    vivo al estado local, persistir en `update()` al soltar). Segundo dedo unido al gesto →
    modo resize: `size = baseSize * dist/dist0`, clamp 0.05..0.3.
  - **Sin hit y scale == 1 → tap (release sin desplazamiento):** coloca el sello activo en
    el punto. Si no hay sello activo en la paleta, no hace nada.
  - **Sin hit y scale > 1:** pan normal (comportamiento actual).
- Pinch con dos dedos sobre vacío en modo editar sigue cambiando el zoom (no conflictivo:
  el pinch resize solo se activa con hit sobre un sello).

### 5. Flujo de datos

```
Edición (gesto) → estado local (x,y,size) → StampRepository.update()  [al soltar]
StampRepository.observe(hash) → collectAsState → render overlay por página.
Sellos filtrados por `page == currentPage` en el render.
```

## Archivos

Nuevos:
- `library/Stamp.kt` (entidad), `library/StampDao.kt`, `library/StampRepository.kt`
- `ui/viewer/StampGeometry.kt`, `ui/viewer/StampView.kt` (painter + palette item + enum)
- tests: `ui/viewer/StampGeometryTest.kt`, `ui/viewer/StampSymbolTest.kt`

Editados:
- `library/AppDatabase.kt` (v2 + Migration + stampDao)
- `library/LibraryRepository.kt` (cascade delete)
- `FOSScoreApp.kt` (stampRepository)
- `ui/viewer/PdfViewerScreen.kt` (edit mode, overlay, gestures, palette, sheetHash)
- `MainActivity.kt` (pasar sheetHash al visor)

## Tests (JVM, sin frameworks)

- `StampGeometryTest`: `fitRect` para apaisada (slot vertical), retrato (slot horizontal) e
  igual; roundtrip normalizado↔px (epsilon); hit-test dentro/fuera.
- `StampSymbolTest`: 14 símbolos, ids únicos, labels no vacías.

## Criterios de aceptación

- `:app:assembleDebug` + `:app:testDebugUnitTest` verdes (14 existentes + nuevos).
- En un dispositivo: activar Editar coloca/mueve/redimensiona sellos que persisten al
  cerrar y reabrir el visor, y sobreviven a zoom y cambio de página.