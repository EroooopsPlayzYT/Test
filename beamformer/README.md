# Beamformer

An Android app that steers a listening beam using a phone's own microphones,
shows a live radar of where sound is coming from, and records both the
beamformed output and the raw channels for offline work.

Native Kotlin, Jetpack Compose, no third-party runtime dependencies. The
beamforming maths lives in a separate plain-JVM module, [`:core`](../core),
so it can be unit tested without a device.

## The constraint this app is built around

Three microphones on a spec sheet does not mean an app gets three channels.
Most Android phones mix every microphone down to one mono stream before an app
ever sees it; a few accept a stereo request and hand back the same signal
twice; only some genuinely deliver two independent channels. There is no API
that tells you which of these a given phone does, and no database to look it
up in.

So the app doesn't assume — it measures. On first launch it sweeps every audio
source, channel count and sample rate combination the platform exposes,
captures a fraction of a second of real audio from each, and checks whether
the resulting channels are actually different (by cross-correlation, not just
"did it open"). The verdict is shown plainly, with a share button that copies
the full sweep as text, because whether a given phone cooperates is exactly
the kind of fact worth reporting back.

Three outcomes:

| Verdict | What it means | What the app does |
| --- | --- | --- |
| **Two independent channels** | Real stereo capture. | Live beamforming and direction finding. |
| **Stereo accepted, mono delivered** | The device said yes and lied — both channels are bit-identical. | Switches to simulation; there is no arrival-time difference to measure. |
| **One channel only** | Honest, and the common case. | Switches to simulation. |

**Simulation mode** runs synthetic sources through the identical beamformer
and direction finder the live path uses — it is not a separate demo, it is the
same code fed different input — and is labelled **SIMULATED** directly on the
radar display, not just in a settings screen, so a screenshot is never
ambiguous about what it shows.

## Why a Bluetooth headset cannot be the array

A headset with several microphones — three, in some models — still hands the
phone one already-processed mono stream over the hands-free profile: the
headset runs its own noise reduction and beam selection internally, and no
Android API reaches its individual capsules. Fusing that stream with the
phone's own microphones into one array is not possible either — two capture
streams on unrelated clocks, with Bluetooth's tens of milliseconds of jitter,
cannot be aligned to the fractions of a millisecond that beamforming actually
measures.

So the headset gets one honest job here: a **reference recording** of its own
processed output, captured alongside a phone-array recording of the same
scene, so the two can be compared by ear. It is never treated as a microphone
array, and the app says so on screen rather than quietly producing numbers
that would look plausible and mean nothing.

Opening the headset's microphone also drops it out of high-quality playback
(A2DP) into the narrowband voice profile (HFP), so it is a poor choice for
monitoring the beam at the same time. **Wired or USB headphones are the
recommended way to listen**, and the app names the connected route and flags
Bluetooth's 150–300 ms of added latency when that's what's available.

## What the two element limit means in practice

Two microphones measure exactly one number about direction: the arrival-time
difference along the line between them. Every direction at the same angle to
that line — a cone around the array's axis — produces the same measurement, so
the app reports a bearing over 0–180°, not 0–360°, and cannot tell a source in
front of the phone from its mirror image behind it. The radar is drawn as a
half disc for exactly this reason: a full circle would claim a resolution the
hardware doesn't have.

Directivity has a hard ceiling too. For two omnidirectional elements the best
possible pattern (a hypercardioid) reaches 6.02 dB of directivity index —
[proven and asserted directly](../core/src/test/kotlin/com/eroooops/beamformer/dsp/BeamformerTest.kt)
in the core test suite. The app's superdirective mode is designed to approach
that limit at low frequency, where a phone-sized array otherwise has almost no
directivity at all: plain delay-and-sum on a 14 cm spacing barely
discriminates below a kilohertz, because the wavelength there dwarfs the
spacing and both microphones see nearly the same pressure regardless of
direction.

Reaching for that low-frequency gain costs something: the minimum-variance
weights that produce it also amplify microphone self-noise, more so as the
loading that stabilises them is relaxed. The **noise floor slider** in the
Beam card controls exactly that trade — how much hiss amplification the
weights are allowed, in dB — and the app shows the resulting **directivity
index** and **noise amplification** as two separate, honest numbers rather
than one blended "quality" score.

Direction finding is further restricted to frequencies below the array's
**spatial aliasing frequency**, `c / 2d` — about 1.2 kHz at a 14 cm spacing.
Above it, phase differences between the microphones wrap around and become
indistinguishable from a different direction; including those bins would not
sharpen the bearing estimate, it would manufacture phantom peaks. This band is
shown on screen (Array card) rather than silently applied.

## What it measures and shows

| Screen element | What it is |
| --- | --- |
| **Polar radar** | Steered-response power (SRP-PHAT) across bearings, a needle for the loudest direction, and a wedge for where the beam is currently steered. Tap or drag to steer. |
| **Waterfall** | Bearing history over time — a moving source draws a diagonal, a fixed one draws a line, and an empty room draws mush. |
| **Beam vs unsteered** | The instantaneous difference between the beamformed output and a plain average of the same microphones — depends on what's making noise right now. |
| **Directivity index / noise amplification** | Properties of the current weights alone, independent of what's happening in the room — the array's actual capability. |
| **Array card** | Measured or assumed spacing, the aliasing frequency, the direction-finding band, and a plain-language note on where the geometry came from. |
| **Capability probe** | The full device sweep described above, with a share button. |

## Recording

Each session saves two files: the beamformed mono output, and the raw
multi-channel capture beside it. The raw file is the point — it's what makes
it possible to re-run the same recorded scene through a changed beamformer
later, off the phone, rather than needing to hold the device and repeat the
experiment every time a weight or threshold changes.

Files are written to app-scoped storage (no storage permission needed) and
shared out through a content URI.

## Permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Runtime. The app does nothing without it. |
| `MODIFY_AUDIO_SETTINGS` | Routes the monitor output; starts/stops the Bluetooth voice link for the reference recording. |
| `BLUETOOTH_CONNECT` | **Optional**, API 31+. Only used to name the connected headset. Denying it leaves the headset unnamed and phone-array beamforming completely unaffected. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Keeps capture running with the screen off. Only started while actually capturing from a microphone — never during simulation, which the platform would otherwise be entitled to refuse. |
| `POST_NOTIFICATIONS` | API 33+, needed for the capture service's own notification to be visible. |

## Building

```sh
./gradlew :beamformer:assembleDebug
```

The APK lands in `beamformer/build/outputs/apk/debug/`. CI builds it on every
push, and runs `:core:test` first — the DSP is checked against physics
(hypercardioid directivity limits, GCC-PHAT bearing accuracy under noise, exact
STFT reconstruction) before either APK is assembled.

Requires JDK 17 and the Android SDK (compileSdk 35). Minimum supported device
is Android 7.0 (API 24), the version `AudioSource.UNPROCESSED` first became
available.

## Layout

```
core/src/main/kotlin/com/eroooops/beamformer/
├── dsp/    FFT, STFT, geometry, delay-and-sum + superdirective weight design,
│           GCC-PHAT / SRP-PHAT direction finding, the array simulator
└── wav/    WAV file writer

beamformer/src/main/java/com/eroooops/beamformer/
├── MainActivity.kt
├── audio/  CaptureProbe, MicGeometry, AudioEngine (capture + beamform +
│           monitor, one dedicated thread), BluetoothAudio, SessionRecorder,
│           the foreground capture service
├── data/   Settings and the recordings index
└── ui/     Compose screen, polar radar, waterfall, panels, the ViewModel
```
