# FOSScore

> Lector de partituras FOSS para Android.
> Inspirado en una app de iOS similar, pero libre y sin anuncios.

## Features

- [ ] **Visor PDF** optimizado para partituras (MuPDF) — retrato, paisaje, two-up, half-page turns
- [ ] **Reflow** — detección automática de sistemas musicales para visualización tipo teleprompter horizontal
- [ ] **Biblioteca con metadatos** — compositor, género, tonalidad, dificultad, duración, etiquetas, valoración
- [ ] **Búsqueda y filtros** — browsing dinámico sin carpetas
- [ ] **Setlists** — agrupa partituras para tocarlas en orden, con navegación entre archivos
- [ ] **Anotaciones** — dibujo, texto, sellos musicales, capas múltiples. Soporte para stylus (S Pen, etc.)
- [ ] **Pistas de audio** — asocia audio a cada partitura, ajusta velocidad/tono, loop, cambio de página automático
- [ ] **Herramientas integradas** — metrónomo, afinador, diapasón, piano virtual
- [ ] **MIDI / Bluetooth** — pedales de cambio de página (PageFlip, AirTurn, etc.)
- [ ] **Marcadores y banderas** — navegación rápida dentro de archivos largos
- [ ] **Enlaces y botones** — repeticiones, acciones programables en la página
- [ ] **Reordenar páginas** — insertar, duplicar, eliminar, dividir archivos
- [ ] **Tema oscuro** — modo claro/oscuro/automático, atenuado de página para poca luz
- [ ] **100 % offline first** — tu música siempre contigo
- [ ] **Sin telemetría, sin anuncios, sin cuentas obligatorias**

## Stack

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Renderizado PDF:** MuPDF
- **Base de datos:** Room (SQLite)
- **Audio:** ExoPlayer / Media3
- **MIDI:** framework MIDI de Android

## Estado

En desarrollo temprano. PRs, issues y sugerencias bienvenidas.
