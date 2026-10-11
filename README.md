# take taker

A small Android multitrack recorder: plug in a class-compliant USB audio interface (built around the
4× mono output of the FM-1 / melodee firmware), split its channels into named mono or stereo tracks,
record, trim, and save as lossless stems and/or an MP3 mixdown.

> **⚠️ Disclaimer: this is vibecoded.** It was written in one sitting with an AI coding assistant
> (Claude Code), tested on a single phone with a single device, and has had no real review.
> It works on the happy path. Treat it as a toy / starting point, not as something to trust a
> once-in-a-lifetime take to. No warranty, see LICENSE.

## What it does

- **01 source** — pick one or more inputs (USB interfaces, phone mics). Hot-plug aware.
- **02 setup** — per-channel live meters, name channels, link neighbours into stereo tracks,
  pick sample rate (defaults to the highest any selected source offers).
- **03 take** — record / restart (discard) / play / trim / save. The timeline auto-zooms out
  (10 s → 5 min window) with a 1 s / 10 s / minute tick hierarchy so zooming keeps its feel;
  pinch to zoom, drag to pan, double-tap to follow again, tap to place the play cursor.
- **trim** — drag in/out handles, play the selection.
- **save** — multitrack (one FLAC per track), multitrack + MP3 mix, or MP3 mix only.
  Files land in `Music/TakeTaker/<name>_<yyyy-MM-dd_HHmm>/`.

## Known limitations

- **Two capture paths for USB interfaces**, switchable per device on the source screen:
  - **raw** — the app claims the device's USB audio streaming interface and reads the isochronous
    stream itself (`src/main/cpp/usbiso.c`, usbfs, no root). Every channel, untouched. Needs a
    one-time USB permission; Android can't use the device while the app holds it. USB Audio
    Class 1 only for now (UAC2 devices fall back to system).
  - **system** — the normal Android audio path. Works with anything, but is subject to the vendor
    audio policy: on a Galaxy S23 Ultra / Android 16 the HAL opens the FM-1 at 4 channels and then
    downmixes to stereo for apps. The app detects this and says so.
- Multiple sources record side by side with best-effort sync; independent device clocks will drift
  slowly over long takes.
- Raw takes are buffered uncompressed in the app cache until saved (~700 KB/s for 4 ch @ 44.1 kHz).
- MP3 is encoded with a pure-Java LAME port ([jump3r](https://github.com/Sciss/jump3r)), so mixdowns
  of long takes are slow-ish.
- The FLAC encoder is a small hand-rolled one (fixed predictors + Rice coding). Output is verified
  bit-exact against ffmpeg in `EncoderTest`, but it compresses a bit worse than libFLAC.

## Build

Android SDK 36, NDK 28.2 (the one C file is compiled by a small Gradle task calling the
NDK's clang directly; ndk-build can't cope with spaces in the project path), JDK 17+.

```bash
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew :app:testDebugUnitTest` writes test FLACs and expected samples to `app/build/enc-test/`.

## Layout

```
app/src/main/java/app/taketaker/
  AppModel.kt        state machine (source → setup → live ⇄ trim, save)
  audio/Engine.kt    one capture thread per input (AudioRecord or raw USB), meters, takes to disk
  audio/UsbRaw.kt    UAC1 descriptor parsing + JNI binding for the iso reader
cpp/usbiso.c         isochronous IN streaming over usbfs
  audio/Take.kt      raw float32 per channel + peak summaries
  audio/Flac.kt      FLAC encoder
  audio/Exporter.kt  stems / mixdown / MediaStore publishing
  ui/Timeline.kt     ruler, waveforms, gestures, trim handles
  ui/Screens.kt      the screens
```

## Credits

- Fonts: [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and
  [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both SIL Open Font License 1.1.
- MP3: [jump3r](https://github.com/Sciss/jump3r) (LGPL), a Java port of LAME.
  `javax/sound/sampled/AudioFormat.java` is a minimal stub so it runs on Android.

## License

MIT for the app code. Bundled fonts remain under the OFL; jump3r under the LGPL.
