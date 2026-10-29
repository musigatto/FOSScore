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
- **MusicXML layout:** Verovio 6.3.0, built from its release source by the `musicxml` module (downloaded by the first build, NDK; see `musicxml/README.md`)
- **Database:** Room (SQLite)
- **Audio:** ExoPlayer / Media3
- **Score playback:** our own SoundFont sampler in Kotlin (SF2 and SF3, no native code) playing the
  MusicXML score live, with the GeneralUser GS bank in SF3 (9.2 MB in the APK)
- **MIDI:** Android MIDI framework

## License

**AGPL-3.0** (see [LICENSE](LICENSE)). Copyright (c) 2026 musigatto.

FOSScore links **MuPDF**, which is AGPL-3.0, so the combined work must be distributed under the
AGPL. In practice:

- The complete source code of the app is public.
- Google's proprietary libraries (Play Services, AdMob, Crashlytics, ML Kit) cannot be used, so no
  ads, no telemetry.
- Shipping this inside closed-source software would need a commercial MuPDF licence from Artifex.

### Third-party components

| Component | Used for | License | Source |
|---|---|---|---|
| [MuPDF](https://mupdf.com) 1.28.5 (`com.artifex.mupdf:fitz`) | drawing PDF pages, and MusicXML pages from Verovio's SVG | AGPL-3.0 | <https://github.com/ArtifexSoftware/mupdf> |
| [Verovio](https://www.verovio.org) 6.3.0 | laying out MusicXML scores | LGPL-3.0-or-later | <https://github.com/rism-digital/verovio>, release `version-6.3.0`, unmodified, downloaded by the build into `musicxml/third_party/` |
| [Leipzig](https://github.com/rism-digital/leipzig) music font | music symbols drawn by Verovio | SIL Open Font License 1.1 | shipped with Verovio (its `fonts/` folder) |
| [Bravura](https://github.com/steinbergmedia/bravura) music font (Daniel Spreadbury, Steinberg) | stamps in the annotator (fingering, dynamics, articulations, accidentals), and fallback for symbols missing from Leipzig | SIL Open Font License 1.1 | `app/src/main/assets/fonts/bravura.otf`; Verovio's copy ships with Verovio |
| [Liberation](https://github.com/liberationfonts/liberation-fonts) text font | text in the layout (titles, lyrics) | SIL Open Font License 1.1 | shipped with Verovio |
| [GeneralUser GS](https://schristiancollins.com/generaluser.php) 2.0.3 (S. Christian Collins) | the instruments of the score playback | GeneralUser GS License v2.0: free use, modification and redistribution | `app/src/main/assets/soundfont/`, its samples converted from SF2 to Ogg Vorbis (SF3, quality 0.6), its license text next to it |

Verovio is built as its own shared library (`libverovio.so`), which the JNI bridge
(`libverovio_jni.so`) loads at run time: a modified Verovio can replace it, as the LGPL requires.
Its full source is the release above. The license texts are in its `COPYING` and `COPYING.LESSER`
(GPL and LGPL) and on the SIL site (OFL).

## Status

Early development. `dev` is the integration branch; `main` is always installable.

## Contributing

- Work on `dev`, with small, independent PRs (one idea per PR).
- Every push to `dev`/`main` runs CI: `assembleDebug` + `lintDebug` + `testDebugUnitTest`.
- PRs, issues and suggestions are welcome.
