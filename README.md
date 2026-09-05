![Samsung](https://img.shields.io/badge/Samsung-1428A0?logo=samsung&logoColor=fff&style=for-the-badge) ![Python](https://img.shields.io/badge/Python-3776AB?logo=python&logoColor=fff&style=for-the-badge)

[![Release](https://github.com/twojstar/wambridge/actions/workflows/release.yml/badge.svg)](https://github.com/twojstar/wambridge/actions/workflows/release.yml) [![code license](https://img.shields.io/github/license/twojstar/wambridge?label=code&logo=opensourceinitiative&logoColor=white&color=6f42c1&style=flat-square)](https://spdx.org/licenses/ISC) <a href="https://deepwiki.com/twojstar/wambridge"><img src="https://deepwiki.com/badge.svg" alt="DeepWiki"></a>

# WAM Bridge

<img src="https://images.samsung.com/is/image/samsung/pl_WAM550-EN_014_Front_black?$330_330_JPG$" width="128" alt="Samsung Shape M5"><img src="https://images.samsung.com/is/image/samsung/pl_WAM551-EN_014_Front_white?$330_330_JPG$" width="128" alt="Samsung Shape M5">

Windows-first bridge for streaming audio over Wi-Fi to Samsung Wireless Audio
Multiroom speakers, including Shape M5 (`WAM550`/`WAM551`).

The CLI serves a tokenized local stream and starts it through Samsung's
`SetUrlPlayback` API. The foobar2000 output component sends whatever foobar is
playing, including internet radio. Finite share/DLNA playback is protocol-proven
but not integrated.

Everything here was measured against one physical Shape M5 (`SPK-WAM550`,
firmware `WAM550WWB-3117.1`). Other models in the family are untested.

## Status: working [`alpha`](https://github.com/twojstar/wambridge/releases/tag/alpha)

Both paths play audio on real hardware. The foobar component passed its full
physical checklist on 2026-08-02: a complete 213-second track start to finish at
a median 1.00x with every sample between 0.9x and 1.1x, seek, pause and resume,
an unattended transition into the next track, internet radio across a 44.1 to
48 kHz switch, and a clean shutdown leaving no FFmpeg or helper behind.

What works:

- SSDP discovery, saved devices resolved by stable device ID, playback control,
  custom radio stations and native TuneIn presets from the CLI.
- Android adapter with automatic speaker discovery, Wi-Fi handoff recovery, UPnP renderer,
  Quick Settings tile and widgets, plus native TuneIn browsing with artwork and speaker-side
  playback controls.
- foobar2000 2.x x64 output: `f32le → FFmpeg FLAC → local HTTP → speaker`.
- `Playback → WAM Bridge` with emergency stop, standby and raw volume steps.
- Configuration through `%LOCALAPPDATA%\WAMBridge\foobar.ini`.

### The one limitation worth knowing before you install

**The last end-to-end FLAC measurement was 6.7 seconds, before the host buffer was
cut by another two seconds.** On 2026-08-27 the physical M5 stayed stable with
`buffer_extra=0`: the host queue held about 1.9 s instead of 3.9 s. A fresh
ear-to-clock total has not been measured yet, so the old 6.7 s figure is historical
rather than a claim about current stock behavior.

Playback itself is unaffected — the stream runs at wall-clock speed and the
seekbar is honest. **Pause, stop and skip still carry pipeline latency** because they act
through the PCM path; with `hardware_volume=1`, the volume slider is routed to the speaker instead. Lowering
the bitrate makes the speaker prebuffer worse rather than better, and raising it helped by
about a second in the older measurements. The remaining latency-sensitive controls belong
on the speaker's own `55001` command path, which answers in about a second, rather
than to keep shortening the audio path; that work is in progress.

Everything else is documented honestly, including the approaches that failed:

- helper isolation (PR #2) and the output clock (PR #21) are merged,
- manual pacing (PR #4) and the large share experiment (PR #7) are closed, with
  their measured conclusions kept.

Current architecture, failed approaches and continuation notes are in
[`docs/DEVELOPMENT_STATUS.md`](docs/DEVELOPMENT_STATUS.md). Measured protocol
facts from a physical `SPK-WAM550` are in
[`docs/WAM_PROTOCOL.md`](docs/WAM_PROTOCOL.md).

## Foobar2000 output

Rolling prerelease,
[`alpha`](https://github.com/twojstar/wambridge/releases/tag/alpha), rebuilt whenever `main`
moves. The link does not change and both halves always come from the same commit. The
version comes from `version` in `pyproject.toml`, the only place it is written down:

- `foo_out_wam.fb2k-component` - the foobar2000 output
- `wambridge-<version>-alpha.<date>.<commit>.apk` - the Android adapter
- a source archive

Open the `.fb2k-component` file with foobar2000 2.x x64, then select:

```text
Preferences → Playback → Output → Samsung M5 (Wi-Fi)
```

This is alpha software built by one person against one speaker. It works, and it
is not polished: the preferences page is new and the INI is still the fuller
file, and the control latency above is real. If you own a Shape speaker and were
looking for exactly this, it should serve you — just read the limitation first.

Configuration, known limitations and the physical checklist are documented in
[`foobar/README.md`](foobar/README.md).

## Requirements

- Python 3.14+
- FFmpeg available in `PATH`
- computer and speaker reachable in the same LAN
- Windows 10/11 and foobar2000 2.x x64 for the native output component
- Windows Firewall access for the bridge on private networks

## Install the CLI

```powershell
git clone https://github.com/twojstar/wambridge.git
cd wambridge
py -m venv .venv
.\.venv\Scripts\Activate.ps1
py -m pip install -e .
```

Confirm FFmpeg is visible:

```powershell
ffmpeg -version
```

## Discover speakers

```powershell
wambridge --discover
```

Discovery sends repeated SSDP requests through active IPv4 adapters. When old
firmware stays silent, it can fall back to checking Samsung's API on port
`55001` in nearby `/24` networks.

Useful diagnostics:

```powershell
wambridge --discover --verbose
wambridge --discover --interface 192.168.1.25
wambridge --discover --no-scan
```

## Saved devices

Save a speaker under an alias instead of relying on its DHCP address:

```powershell
wambridge --speaker 10.0.0.118 --remember M5
wambridge --list-devices
wambridge --device M5 --probe
wambridge "D:\Music\track.opus" --device M5
```

The profile stores the stable Samsung `device_id` and caches the latest working
IP. If the address changes, WAM Bridge searches the LAN for the same device and
updates the profile.

On Windows profiles are stored in:

```text
%LOCALAPPDATA%\WAMBridge\devices.json
```

The foobar2000 component passes the configured alias to the same helper and
profile resolver.

Remove a saved profile:

```powershell
wambridge --forget M5
```

## Startup volume safety

The tested Shape M5 firmware uses raw API volume steps `0..30`. Values above 30
are silently clamped to maximum while still returning success. The current
client has not yet implemented model-aware percentage conversion, so treat
volume arguments as raw M5 steps:

- `3` is approximately 10 percent,
- `6` is approximately 20 percent,
- `30` is maximum.

Old WAM firmware may jump to a high level while switching to URL playback. WAM
Bridge mutes the speaker, starts the stream with 1.5 seconds of silence and then
applies the requested bounded step after decoding begins.

Choose a cautious explicit level:

```powershell
wambridge "D:\Music\track.opus" --device M5 --volume 3
```

Change only the startup ceiling while preserving quieter current settings:

```powershell
wambridge "D:\Music\track.opus" --device M5 --max-start-volume 3
```

## Remote control

```powershell
wambridge --device M5 --status
wambridge --device M5 --set-volume 3
wambridge --device M5 --mute
wambridge --device M5 --unmute
wambridge --device M5 --pause
wambridge --device M5 --play
wambridge --device M5 --stop
wambridge --device M5 --standby
```

Native providers such as TuneIn use their CPM commands. DLNA uses UIC pause and
resume. Samsung URL playback cannot be resumed reliably, so starting the source
again creates a new session.

## Radio stations

Save a direct HTTP or HTTPS audio stream. Fallback URLs are tried in order:

```powershell
wambridge --radio-add paradise "https://primary.example/radio.mp3" `
  "https://backup.example/radio.ogg"
wambridge --radio-list
wambridge --radio-play paradise --device M5 --volume 3
wambridge --radio-remove paradise
```

A station can also carry its TuneIn id. The id is resolved when the station is played, so it
follows the broadcaster if the stream address moves - the URLs saved beside it stay as
fallbacks for when TuneIn cannot be reached or offers nothing usable:

```powershell
wambridge --radio-add trojka "http://41.dktr.pl:8000/trojka.ogg" --tunein-id s15984
```

Station definitions are stored in:

```text
%LOCALAPPDATA%\WAMBridge\stations.json
```

Import the bundled BBC Radio 1, PR3 Trójka and PR4 Czwórka pack:

```powershell
wambridge --radio-import top3
wambridge --radio-list
wambridge --radio-play bbc1 --device M5 --volume 3
wambridge --radio-play trojka --device M5 --volume 3
wambridge --radio-play czworka --device M5 --volume 3
```

These are WAM Bridge stations and do not overwrite the three presets selected
by the physical button on the speaker.

## Native TuneIn presets

Read and start TuneIn presets already stored by the speaker:

```powershell
wambridge --device M5 --tunein-list
wambridge --device M5 --tunein-play 0 --volume 3
wambridge --device M5 --tunein-play "Radio Paradise" --volume 3
```

Changing the speaker's TuneIn account or preset list still belongs to Samsung's
plugin because no reliable write API is known.

## Browsing and searching TuneIn

The whole TuneIn catalogue is reachable without saving anything as a preset.
`--tunein-browse` walks the tree; a path is the row numbers of the pages above,
separated by slashes. `--tunein-search` finds a station by name. Both are
paginated, so `--tunein-start` moves the window.

```powershell
wambridge --device M5 --tunein-browse
wambridge --device M5 --tunein-browse 1/0
wambridge --device M5 --tunein-search "Radio Paradise"
wambridge --device M5 --tunein-search "jazz" --tunein-start 30
```

Rows print as `index<TAB>kind<TAB>mediaid<TAB>title`. The `mediaid` is the stable
TuneIn station id, so it is what goes into `--radio-add --tunein-id`; the leading
index only means "this row on this page" and changes as you move.

The speaker keeps the browse cursor itself, so a search leaves it in TuneIn's
search results rather than the catalogue. `--tunein-browse` notices and steps
back on its own.

## Direct playback

```powershell
wambridge --speaker 192.168.1.50 --probe
wambridge "https://example.net/radio-stream" --speaker 192.168.1.50
wambridge "D:\Music\track.opus" --speaker 192.168.1.50
wambridge "D:\Music\track.ogg" --speaker 192.168.1.50
```

Use MP3 output when FLAC is unstable on a particular firmware:

```powershell
wambridge "D:\Music\track.opus" --speaker 192.168.1.50 --format mp3
```

When exactly one WAM speaker is discovered, `--speaker` may be omitted.

## Notes

- The local URL stream uses HTTP/1.0 without chunked transfer for old-firmware
  compatibility.
- The URL contains a random session token and exists only while the command is
  running.
- Do not expose port `55001` or a bridge HTTP port to the internet.
- The tested M5 does not expose a standard UPnP AVTransport renderer. See the
  protocol notes before restarting generic UPnP work.
- `SetUrlPlayback` may freeze malformed firmware when the served body is not
  playable audio. The bridge exposes only FFmpeg output and returns `404` for
  other paths.

## Validate

```powershell
py -m unittest discover -s tests -v
```

---

## 📰 Mininewsy

<!--README_FEED:START-->
- [How to Engage with New Media: A Strategic Guide for Nonprofit Organizations](https://carnegieendowment.org/research/2026/08/how-to-engage-with-new-media-a-strategic-guide-for-nonprofit-organizations)
- [How the U.S. Export-Import Bank Can Finally Join the Fight Against Climate Change](https://carnegieendowment.org/research/2026/09/renewable-energy-investment-united-states-exim-export-import-bank)
- [Wrześniowe Soboty Agatowe. Na polach w Rudnie można wykopać swój skarb. Całe rodziny ruszyły na poszukiwania z młotkami i motykami - Dziennik Polski](https://news.google.com/atom/articles/CBMigwJBVV95cUxORHJrU3lQU0JreFdBeEY2ZEswazltaW1wN2ExV3N2RUpCLXdaT3dEdXRHLTlDcjdSTzlwZWhjaEx3cU5iT1lSeUFCY1ROc090SUk4cFhCVFNYYzFLQkU0YWVCX0hiUGR2YURRc0J0V1B5YmZveWVVbWFnbndQSnQ0NzZBVGs4UTdWYUV5QUZwTTJlcjc5UXhyU19faUVTY3JUQWlhNHkteGZ1Xzg2TlZoR1hIb1FRbjZ6dHZOMkF0YXF4eHlxaUp3eGVhcTczd3dCdTBxLUhYQmd6R0QyazQ3aXBWSGdsMktqX1JBUUMxT0Nud1hlMTJqWFBFc1JuMjA0OEVv?oc=5)
- [WOW! Ten zwiastun jest tak dobry, że twórcy muszą udowadniać, że to nie sztuczna inteligencja](https://antyweb.pl/wow-ten-zwiastun-jest-tak-dobry-ze-tworcy-musza-udowadniac-ze-to-nie-sztuczna-inteligencja)
- [Ubisoft nie uczy się nawet na własnych sukcesach. Heroes III Remake to obnaża](https://antyweb.pl/ubisoft-nie-uczy-sie-nawet-na-wlasnych-sukcesach-heroes-iii-remake-to-obnaza)
- [Nietrzeźwy pieszy wbiegł na czerwonym świetle. Potrącenie na ulicy Dąbrowskiego - oswiecimonline.pl](https://news.google.com/atom/articles/CBMirgFBVV95cUxOQlBRWS1jVlNiRnBoQkFaYjJOTVZQS1VSLVhReFo0ZXF4QVMwUTFjaW00MWdHdnNEWHE3Zm9wQkEyTEczaGIyalQzSzJOZ2ZlVGJYb2xQaVREWEZMck55WXQyR0xNSURob0kxNFNKd0lTUzYtUzZxX1pPZkFSTGNMcmJHTmFSUE9La1hyWHNrTFZRLTBGbVdKUnFsd1RWTl9Tc010S2pvcG1FN2VUQVE?oc=5)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝Hard disks are so sensitive to vibration, that just screaming at them diminishes their performance.❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
