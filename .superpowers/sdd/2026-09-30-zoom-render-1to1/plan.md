# Plan: zoom por re-render 1:1 (adiós al "cocer"/shaking)

Aprobado por el usuario en conversación ("yeah go"). Los visores de referencia
(AndroidPdfViewer, vfr/Viewer, GrapheneOS) NO escalan la página con un transform:
re-renderizan al zoom actual. Por eso la GPU nunca reescala la textura y nada
"cuece".

## Objetivo

En modo página única: el bitmap se renderiza al tamaño exacto que ocupa en
pantalla (encaje × zoom) y se dibuja 1:1 (sin transform de escala). El overlay
(tinta, sellos, recuadro de selección) se escala como vector dentro del propio
dibujo (se re-rasteriza cada frame: nítido a cualquier zoom, cero reescalado de
GPU). Durante el pellizco se re-renderiza limitado (escalones del 10% del encaje,
mín 150 ms entre renders); al soltar, render final tras asentarse.

Modos de lectura (two-up, media página): se mantienen como hoy (capa con escala),
son modos de lectura, no de ampliación. Sin regresión.

## Tareas

1. **ZoomMath** (`ui/viewer/ZoomMath.kt`): funciones puras del zoom —
   `anchoredOffset` (anclaje con base de centrado), `clampOffset` (rango de paneo
   en contenido), `renderBucket` (escalón de render). Test JVM primero.
2. **Wiring del render**: sustituir `quality: Int` (1..2) por escalones de zoom
   (`renderScale: Float` en bucket 0.1) que re-renderizan el slot del encaje ×
   zoom; el gesto lo alimenta (min 150 ms durante el pellizco, 200 ms al soltar).
   MuPdfDoc sin cambios (render(.., quality=1) con hueco = encaje×zoom).
3. **Dibujo 1:1 en página única**: fuera la escala del graphicsLayer; bitmap a
   su resolución en `(base + offset)`; overlay en coords de encaje con
   `translate(base+offset) + scale(zoom)`; sellos en `pos + r*zoom`.
4. **Gestos**: conversión pantalla→encaje en las operaciones de contenido;
   anclaje y doble toque con `(c - base)`; clamp de contenido; igual para spread.
5. **Verificación**: `:app:testDebugUnitTest` verde; compileDebugKotlin o
   equivalentes que el entorno permita. El usuario valida en la S6 Lite.
6. **Commit** local (sin push, como siempre).

## Fora de alcance (rulings)

- Spread/½-página: zoom actual (capa), sin re-render. Ruling: son modos de
  lectura; zoom ahí es marginal y el coste de re-render doble no compensa.
- Tiles (render de solo el recuadro visible): queda documentado como upgrade
  path en MuPdfDoc.MAX_RENDER_PIXELS; con MAX_ZOOM 2.5 y fit ~0.5-1M px la página
  entera cabe en 4M px.
- `compositingStrategy = Offscreen` se retira de página única (ya no hace falta;
  en spread se conserva tal cual estaba).

## Ejecución (2026-09-30)

- **1 ✅** `ZoomMath.kt` + `ZoomMathTest.kt`. TDD: el test no compilaba antes de
  escribir la matemática (RED genuino); ahora 42/42 tests verdes.
- **2 ✅** `quality: Int` → `renderScale: Float` (snapshotFlow + collectLatest:
  sube de bucket en gesto ≥150 ms, afina tras 250 ms de asentamiento). El slot de
  render en página única = encaje × bucket, `MuPdfDoc.render(..., quality = 1)`.
- **3 ✅** Rama de página única reescrita: `Image` 1:1 en `(base+offset)` con
  `ContentScale.FillBounds` (ojo: `ContentScale.Fill` NO existe en Compose 1.10,
  fue renombrado a `FillBounds`); overlay con `withTransform { translate; scale }`;
  sellos en `pos + r*k`. Los modos de lectura conservan `spreadMod` (capa + Offscreen).
- **4 ✅** Gestos: `fitPoint(p) = (p - base - offset)/scale`, `downF`, `currentHitFit`
  como rect de contenido (origen 0), `anchorBase = if (spread) Zero else currentBase`.
  Todos los handlers (release, resizing, move, ink, eraser, doble toque, pellizco)
  convertidos a coordenadas de encaje; umbrales de muestreo `/scale`.
- **5 ✅** `:app:testDebugUnitTest` verde (42 tests). Sin warning nuevo (los 4
  "Condition is always true" son preexistentes).
- **6 ⏳** Commit local sin push.

### Regresiones encontradas y corregidas durante la verificación

- **Páginas sin tamaño pre-cargado**: `PRELOAD_SIZES = 4`; saltar a una página ≥ 5
  daba `pageSize == null` → slot (0,0) → render null → pantalla de error. Arreglado
  con `MuPdfDoc.ensurePageSize` (public, bajo el lock) y resolución del slot dentro
  del worker.
- **Base del anclaje en spread**: usaba `currentBase` (pageFit.left del viewport,
  distinto de 0) en two-up/media → anclaje torcido. Ahora `anchorBase` = 0 en
  spread (la capa escala el viewport entero).
- **Test preexistente roto**: `InkGeometryTest.samplePointsDropsConsecutiveClosePoints`
  esperaba conservar un punto a 1.5 px con umbral 2 px (contradecía la implementación
  y el comentario de `samplePoints`: se mide contra el último conservado). Expectativa
  corregida + comentario. (Igual que `StampSymbolTest`: el repo tenía tests rotos.)
- **Expectativas de `ZoomMathTest`** mal calculadas en el eje Y (601 de alto × 1.5 no
  cabe en 700 de viewport; y `minY ≠ minX`). Rediseñado el caso con ambos ejes dentro
  del viewport y el cálculo correcto de `minX/maxX/minY/maxY`.

### Pendiente del usuario

- Validación en la S6 Lite: zoom sin "cocer" y anclaje estable (página única),
  y que el zoom de lectura (two-up/media) siga igual.

## Segunda ronda: anclaje del pellizco ("no amplía donde quiero; huye hacia la
## esquina inferior derecha; estira un poco verticalmente")

Reporte del usuario tras validar la ronda 1 ("smooth af, sin embargo...").

Causa raíz (sistemática, confirmada con `ZoomGestureSimTest`, un sim JVM del
bucle real del gesto: dedos que se abren con/ sin deslizamiento, cuenta la
deriva del punto de página bajo el centroid):

- `clampAxis` tenía dos ramas: **si el contenido cabe en un eje → offset
  forzado al centro cada frame**; si desborda → cobertura total. Resultado:
  1) el anclaje del pellizco se anulaba mientras el eje cupiera (para una
  partitura apaisada en viewport retrato, hasta 2.12× — casi todo el rango de
  zoom): la página crecía desde el CENTRO y el punto bajo los dedos huía hacia
  la esquina (40-130 px de deriva media segun trayectoria en el sim);
  2) al cruzar de "cabe" a "desborda", el rango se colapsaba a un punto y el
  offset anclado saltaba 40-130 px en un frame. El "estiramiento vertical" es
  el mismo síntoma perceptivo: al crecer desde el centro, la zona bajo los
  dedos no sigue el pellizco (el render y el bitmap conservan SIEMPRE el
  aspecto de la página — verificado en MuPdfDoc.render y en la caja `pageFit*k`).
- Fix: **rango único y continuo** en `clampAxis` — la página puede deslizarse
  mientras siga tocando el viewport (`[-scaled - base, viewport - base]`),
  sin saltos al cruzar; a escala 1 la vista completa sigue centrada. Precio
  documentado: al desbordar, un paneo puede dejar la página un poco fuera por
  un lado (upgrade: clamp de cobertura total al soltar el gesto).
- Verificación: `ZoomGestureSimTest` (nuevo, 4 casos) y `ZoomMathTest`
  (semántica nueva) verdes; suite completa verde. Sim tras el fix: dedos
  estáticos 0.0001-0.0003 px de deriva; deslizantes ~4 px (residuo intrínseco
  del anclaje incremental ∝ viaje del centroid, física del pellizco, no clamp).
- Commit local sin push (como toda la sesión).
## Tercera ronda: el reporte persiste tras el fix del clamp

El usuario valida la ronda 2 y reporta: "honestamente, pasa lo mismo: el zoom
estira el pdf y lo deforma, y solo se dirige a la esquina inferior derecha".

Fase 1 (lectura de codigo actual, sin tocar logica):

- Gesto de pellizco (PdfViewerScreen ~1015-1060): anclaje incremental con
  `anchoredOffset`, `centroid` y `anchorBase` en el espacio del viewport.
  Matematicamente exacto (la sim de la ronda 2 lo confirmo a sub-pixel).
- Dibujo de pagina unica (~1127): Image 1:1 en `box = pageFit*k` con
  `ContentScale.FillBounds`; la caja puede exceder el viewport sin que el Box
  la recorte (Compose no coerce a los maximos del padre). Aspecto coherente.
- Pipeline de aspecto: `pageFit` (composicion) relee `sizes` en cada
  recomposicion; `render` corrige `sizes` con los bounds del display list
  ANTES de publicar el bitmap. El desajuste MediaBox-vs-display (rotacion o
  margenes) es transitorio (1 frame), no persistente.
- Conclusión: el codigo ES coherente en teoria. Los sintomas persistentes
  (estiramiento real + punto fijo en la esquina) requieren datos reales del
  dispositivo que el JVM no puede dar.

Accion (esta ronda): diagnostico en dispositivo, sin tocar la logica:

- `DEBUG_ZOOM_TRACE = true` (temporal): overlay en vivo (viewport, pageBox,
  pageFit, bitmap y sus ASPECTOS, k/rs, offset/pos, residuo del anclaje) + marcas
  visuales (anillo verde en el down, cruz roja en el centroide vivo) + logcat
  `FOSScore-Zoom` (PINCH START / frames rate-limited / PINCH END con drift).
- `PinchStart` captura offset/scale/fitPoint al empezar el 2-dedos; al soltar
  se mide el residuo del anclaje en px de pantalla (`debugDrift`): donde
  acaba el punto de pagina que estaba bajo el centroide al empezar.
- Compila y suite JVM verde (solo warning Room preexistente).

Siguiente paso: el usuario instala el build, hace un pellizco sobre un punto
conocido de la partitura y manda un screenshot (o logcat): si `aspect=NO` ->
caja y bitmap tienen aspectos distintos (fuente del estiramiento); si
`drift > ~10px` -> el anclaje falla en dispositivo (coordenadas/gesto).

## Cuarta ronda: logcat real del S6 Lite — el anclaje es perfecto, el bug es
## que `Modifier.size()` recorta la caja al viewport

El usuario manda `adb logcat -s FOSScore-Zoom` (~2 min de pellizcos varios,
1.0→2.5→1.0, 2.5→2.1→1.0, etc.). Datos: viewport 1200×2000 (density 240),
pageFit 1200×1568 @ base (0,216) — página retrato que llena el ancho.

Conclusiones de los números:

- **El anclaje funciona PERFECTO en dispositivo.** Verificado frame a frame en
  el pellizco sostenido a 2.5×: `fitPoint(c)` bajo el centroide se mantiene en
  ≈(470, 500.0±0.3) px mientras los dedos se mueven 45 px; en el pellizco
  2.5→1.0 con 300 px de viaje del centroide, el punto queda ≈(309, 439±1.5).
  La sim de la ronda 2 tenía razón: `anchoredOffset` es exacto.
- **`drift` al soltar NO es un indicador de anclaje**: mide
  `(anchorBase + offset + ps.fit*scale) − centroid_final`, que a escala 1 tras
  una ida-vuelta colapsa a `|c0 − c1|` = el viaje del centroide (322/188/92/
  413/301/191 px medidos ∝ cuanto se movieron los dedos). La métrica es basura;
  los datos por frame son fiables y dicen que el anclaje está bien.
- **Los renders SIEMPRE tienen el aspecto de la página** (0.7646-0.7650 en
  todos los buckets; el cap de 4M px recorta a 1749×2287 manteniendo aspecto).
  El estiramiento NO viene del pipeline de render.
- **CAUSA RAÍZ DEL ESTIRAMIENTO**: la caja del `Image` en página única usa
  `Modifier.size(pageFit*k)`. En Compose, `size()` **coerciona el tamaño a los
  constraints máximos del padre** (a diferencia de `requiredSize()`). El
  `Image` vive en un `Box(fillMaxSize)` de 1200×2000: en cuanto ambos ejes de
  la caja desbordan (k ≳ 1.28: 1200k > 1200 y 1568k > 2000), el tamaño medido
  se recorta a 1200×2000 (aspecto 0.6) y `ContentScale.FillBounds` estira el
  bitmap (aspecto 0.7648) a la caja recortada → ~27.5 % de estiramiento
  vertical a todo zoom alto, ~10 % ya a k=1.1 (cuando solo desborda el ancho,
  el alto se preserva y el estiramiento es proporcional al desborde).
  "Se va a la esquina inferior derecha": con la caja pegada al viewport por el
  recorte mientras offset/scale corren como si la caja fuera completa, el
  punto fijo visible no coincide con el pellizco y el contenido sobrante queda
  confinado — la página no crece alrededor de los dedos, crece "hacia" la
  esquina que el recorte le deja.
- Fix (un cambio + import): `Modifier.requiredSize(...)` en la caja del Image
  — ignora los constraints del padre, la caja crece de verdad y conserva el
  aspecto de la página (equivalente al graphicsLayer pre-refactor). El overlay
  de sellos/tinta (Canvas fillMaxSize + transform manual) ya era inmune.

Verificación: `:app:compileDebugKotlin :app:testDebugUnitTest` verde (42
tests, sin cambios en tests). Dif: import + requiredSize + comentario ponytail
+ el diagnóstico de la ronda 3 (aún sin commitear).

Siguiente paso: el usuario instala y pellizca. Esperado: sin desformación a
ningún zoom, la página crece anclada donde tocan los dedos (el anclaje ya lo
confirman los datos). Si persiste algún síntoma, ya no es la geometría — sería
percepción del render retardado (bitmap a bucket < k durante el pellizco,
hasta 1.7× de upscale GPU uniforme — techo conocido de MAX_RENDER_PIXELS).
Tras validar: quitar DEBUG_ZOOM_TRACE y commitear.

## Ronda 5 - foco del pellizco fijo al primer contacto (2026-09-30)

Sintoma que queda tras la ronda 4: "un poco mejor, pero no amplia donde quiero".

- **Causa raiz (medida en el logcat del S6 Lite, no supuesta)**: el bucle del
  pellizco anclaba al centroide VIVO cada frame (`anchoredOffset(offset, k, c,
  base)`) y ademas sumaba `offset += delta` (pan de 2 dedos). La matematica del
  ancla era correcta con dedos quietos (±0.3 px, validado en rondas previas), pero
  el FOCO no era el punto tocado: era el centroide movil. En el log el centroide
  recorre ~470 px durante un pellizco real (23.491→25.107) y ~211 px en otro
  (29.693→31.267, con el zoom ya sostenido a 2.5x): el punto de partitura bajo
  los dedos al tocar se escapaba con ese viaje (208 px / 174 px respectivamente).
- Los 3 "PINCH START" en 73 ms son re-enganches por cambio del set de punteros
  (dedo/palma): re-baselinean el ratio, no son el bug.
- **Fix (3 puntos)**: `var pinchAnchor` capturado al detectar los 2 dedos;
  `anchoredOffset` usa ese ancla FIJA en vez del centroide; fuera el
  `offset += delta` de 2 dedos. `pinchFit` no hizo falta como estado (solo lo
  queria el log).
- Tradeoff documentado en `ponytail:`: durante el pellizco ya no se panea con 2
  dedos. Para mover la pagina, levantar un dedo y arrastrar (rama de 1 dedo,
  ya existente) o mover los dedos con el zoom ya hecho.
- **Métrica de debug nueva**: `PINCH END drift` ya no se mide contra el centroide
  final (eso era viaje de dedos, 92-413 px, inservible) sino contra `ps.anchor`,
  el sitio donde se posaron los dedos. Debe salir ~0: si sale grande, el foco se
  escapa de verdad.
- `ZoomGestureSimTest` reescrito a la semántica nueva (5 tests, deriva del foco
  ~0.0001 px). Teeth verificados: pasando la sim al modelo viejo fallan los 3
  casos con deslizamiento de dedos.
- Verde: `:app:testDebugUnitTest :app:assembleDebug` (5/5 en ZoomGestureSimTest,
  42 tests en total). APK: app/build/outputs/apk/debug/app-debug.apk.

Siguiente paso: el usuario instala y hace el gesto que antes fallaba - poner los
dos dedos sobre un compas concreto y separar, dejando los dedos deslizarse. Lo
que TIENE que pasar: el compas se queda clavado donde se posaron los dedos y
crece alrededor. Si aun se mueve, el logcat dira cuanto (drift vs ps.anchor).

## Ronda 6 - el foco es el dedo que APUNTA, no el punto medio (2026-09-30)

Diagnostico cerrado con una captura de pantalla del S6 Lite (adb screencap), no solo con
el logcat. En la pantalla del usuario se veian las dos marcas de diagnostico a la vez:

    circulo verde (primer dedo)  en (200, 985)
    cruz roja    (centroide)    en (455, 1000)     -> 255 px a la derecha

- **CAUSA RAIZ REAL**: el anclaje era el CENTROIDE de los dos dedos. Con un dedo
  plantado en el compas y el otro abriendo en otro sitio, el centroide cae a mitad de
  camino: el compas apuntado se desliza y en pantalla queda delante otro ("si hago zoom
  en el compas 1 se desplaza al compas 15, centro derecha"). No era la matematica
  (correcta al pixel, verificado) ni el dibujo (la caja se dibuja en pageFit+offset,
  coherente con el gesto): era **elegir el punto equivocado como foco**.
- **Fix (1 linea)**: al detectar los 2 dedos, el foco pasa a ser la posicion del puntero
  `down.id` (el primer dedo, con el que se apunta) en vez del centroide. Si ese dedo se
  levanta a mitad de gesto se conserva el foco anterior (no salta al dedo que quede).
  El segundo dedo solo marca el nivel de zoom.
- **Diagnostico eliminado por peticion del usuario**: DEBUG_ZOOM_TRACE, la caja de texto,
  la rueda verde, la cruz roja y todo el logcat FOSScore-Zoom (mas imports huerfanos).
- `ZoomGestureSimTest` reescrito con el gesto real (dedo que apunta quieto + segundo
  dedo que abre y se desliza): 8 tests, deriva del foco ~0.0001 px en los 4 puntos de
  la pagina y en los dos modos de lectura.
- APK instalado en el R52N81FPMRJ con adb. Pendiente: que el usuario verifique el
  pellizco real (adb no puede inyectar multitáctil).
- Ojo al interpretar logs: el log imprime la escala a 1 decimal (f1), asi que
  `off` y `scale` seemed no cuadrar cuando en realidad si: hay que despejar la escala
  del offset (off = (1-s)*(ancla-base)) en vez de fiarse del redondeo.
