# FOSScore

> FOSS sheet music reader for Android.
> Inspired by a similar iOS app, but free software and with no ads.

## Features

Done:

- [x] **PDF viewer** tuned for sheet music (MuPDF) — portrait, landscape, two-up, half-page turns, zoom/pan anchored on the midpoint, animated double tap
- [x] **Library with metadata** — composer, genre, key, tags
- [x] **Ink annotations exportable into the PDF** (`/Annots` ink, 💾PDF button in edit mode)
- [x] **Dark theme** — light/dark/auto, page dimming, negative mode for OLED

Pending (detailed roadmap in the GitHub issues):

- [ ] **Reflow** — detect the music systems and show them as a horizontal teleprompter
- [ ] **Search and filters** — dynamic browsing, no folders
- [ ] **Setlists** — group scores to play them in order, with navigation across files
- [ ] **Stamps and text** — musical stamps into `/Annots` too, text notes
- [ ] **Audio tracks** — attach audio to each score, adjust speed/pitch, loop, automatic page turns
- [ ] **Built-in tools** — metronome, tuner, pitch pipe, virtual piano
- [ ] **MIDI / Bluetooth** — page turner pedals (PageFlip, AirTurn, etc.)
- [ ] **Bookmarks and flags** — quick navigation inside long files
- [ ] **Links and buttons** — repeats, programmable actions on the page
- [ ] **Reorder pages** — insert, duplicate, delete, split files
- [ ] **Performance mode, practice dashboard**
- [ ] **100% offline first** — your music always with you
- [ ] **No telemetry, no ads, no mandatory accounts**

## Compatibility

- Android 6.0+ (minSdk 23), including old 32-bit tablets (`armeabi-v7a`).
- 100% offline: no accounts, no telemetry, no ads.

## Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **PDF rendering:** MuPDF (`com.artifex.mupdf:fitz`, prebuilt AAR with the JNI; no NDK needed)
- **Database:** Room (SQLite)
- **Audio:** ExoPlayer / Media3
- **MIDI:** Android MIDI framework

## License

**AGPL-3.0** (see [LICENSE](LICENSE)). Copyright (c) 2026 musigatto.

FOSScore links **MuPDF**, which is AGPL-3.0, so the combined work must be distributed under the
AGPL. In practice:

- The complete source code of the app is public.
- Google's proprietary libraries (Play Services, AdMob, Crashlytics, ML Kit) cannot be used, so no
  ads, no telemetry.
- Shipping this inside closed-source software would need a commercial MuPDF licence from Artifex.

## Status

Early development. `dev` is the integration branch; `main` is always installable.

## Contributing

- Work on `dev`, with small, independent PRs (one idea per PR).
- Every push to `dev`/`main` runs CI: `assembleDebug` + `lintDebug` + `testDebugUnitTest`.
- PRs, issues and suggestions are welcome.
