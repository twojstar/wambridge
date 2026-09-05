# Architecture

## 1) Architectural Style
Primary style is a protocol core with platform adapters around it. Python owns the most complete measured WAM behavior; foobar delegates live PCM to a packaged Python helper, while Android reimplements only the protocol subset needed for its native lifecycle. The strongest design constraint is firmware behavior measured on the physical M5, especially stateful CPM browsing, port-55001 contention, TCP backpressure, and unrecoverable dead-stream offers.

## 2) System Flow
Desktop file/custom URL radio: `radio_cli` → `cli.run` → `AudioStreamServer`/FFmpeg → local tokenized HTTP URL → `samsung.play_url` → M5 pulls audio.

Native TuneIn preset: `radio_cli` → `_play_tunein_safely` → `play_tunein_preset` → WAM `SetPlayPreset`; the speaker resolves/decodes TuneIn itself, so this path does not use the local `AudioStreamServer` or FFmpeg.

foobar: foobar output callbacks → `WamOutput` → helper process `wambridge-pcm` → `PcmAudioStreamServer`/FFmpeg → M5; helper stdout reports READY/AUDIO_STARTED/PLAYING and a loopback `ControlChannel` carries volume/pause/stop/sleep back without opening another speaker connection.

Android renderer: DLNA/UPnP control point → `UpnpRenderer` → phone-local stream endpoint → `RendererService` + `SamsungWamChannel` → M5. Android radio replaces the control point with `RadioService` + `RadioProxyServer`. `SpeakerTarget.resolveBound` resolves the speaker together with one concrete Android `Network` + IPv4 target; renderer/radio HTTP listeners, source traffic and WAM control reuse that same target so a Wi-Fi handoff cannot mix an old speaker route with a new local endpoint. Endpoint movement rebuilds the renderer and reconnects radio; temporary loss keeps the requested lifecycle in a waiting/retry state.

## 3) Layer/Module Responsibilities

| Module | Owns | Must not own | Evidence |
|---|---|---|---|
| `samsung.py` | request validation, XML, UIC/CPM semantics | CLI/UI state | `src/wambridge/samsung.py` |
| discovery/profiles | speaker selection and stable identity | playback transport | `discovery.py`, `profiles.py` |
| stream/pcm_stream | bounded local HTTP + FFmpeg | WAM command policy | `stream.py`, `pcm_stream.py` |
| `pcm_cli.py` | session state machine and persistent speaker connection | foobar SDK details | `src/wambridge/pcm_cli.py` |
| foobar C++ | SDK callbacks, queue/clock, helper lifecycle | duplicated WAM HTTP client | `foobar/foo_out_wam.cpp` |
| Android services | foreground lifecycle and Android-facing adapters | desktop process assumptions | `RendererService.kt`, `RadioService.kt` |

## 4) Reused Patterns
Adapters wrap a single measured protocol vocabulary rather than copying product logic. Protocol and value records often use dataclasses/data classes; live service/session state such as Android `RendererState` remains mutable and uses explicit synchronization/volatile fields where needed. Local alias stores use versioned records and atomic replacement on Python. Startup is fail-safe: mute first, prove stream/server readiness, release audio, then restore a bounded level.

## 5) Known Architectural Risks
The speaker itself is stateful and fragile: the CPM browse cursor survives processes, fast CPM traffic temporarily wedges that subsystem, and a dead `SetUrlPlayback` target can wedge the control path until power-cycle. The main transport implementations are large state machines (`pcm_cli.py`, `foo_out_wam.cpp`, `UpnpRenderer.kt`), so timing/lifecycle changes need narrow tests plus physical validation.

## 6) Evidence
- `docs/WAM_PROTOCOL.md`
- `docs/DEVELOPMENT_STATUS.md`
- `src/wambridge/pcm_cli.py`
- `src/wambridge/control_channel.py`
- `mobile/README.md`
