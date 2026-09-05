![DLNA](https://img.shields.io/badge/DLNA-48A842?logo=dlna&logoColor=fff&style=for-the-badge) ![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=fff&style=for-the-badge)

# WAM Bridge Mobile Adapter

Android-first adapter for exposing Samsung WAM speakers to mobile players without changing the existing WAM Bridge playback/research code.

## Scope

- Uses measured WAM behavior as a protocol specification, not as a code dependency.
- Confirmed target: Neutron Music Player -> UPnP/DLNA -> Samsung Shape M5; other local UPnP/DLNA players can use the same renderer.

## Current state

The Android adapter provides:

- WAM speaker autodiscovery via SSDP with prefix-aware LAN fallback; starting the renderer
  from the app, Quick Settings tile or widget resolves the saved target and discovers it
  automatically when needed;
- endpoint-aware Wi-Fi recovery: discovery, WAM control and the local renderer/radio proxy are
  bound to the same Android `Network` + IPv4 target; a changed network identity triggers a
  rebuild/reconnect even when DHCP reuses the same address, while temporary Wi-Fi loss waits
  and retries instead of silently abandoning the requested session;
- UPnP MediaRenderer services: AVTransport, RenderingControl and ConnectionManager;
- a local WAV/LPCM, MP3 and FLAC proxy handed to the M5 through `SetUrlPlayback`;
- safe first-start volume capped at M5 raw step `3`;
- idle/session release so stopped playback does not keep the M5 awake;
- a Quick Settings tile: tap toggles the renderer, long-press opens settings;
- a compact toggle widget plus an expanded widget with play/pause, mute and raw-volume controls;
- optional launcher-icon hiding;
- native TuneIn preset browsing and safe playback through the speaker CPM API, with station
  artwork/metadata when TuneIn exposes it and play/pause, mute, raw-volume and confirmed Stop
  controls on the standalone screen;
- saved direct radio stations with optional TuneIn station IDs, resolved at play time ahead of
  ordered fallback URLs and relayed locally by the phone;
- an M5-style app/renderer icon exposed through UPnP for players such as Neutron;

Physical phone + M5 playback through Neutron is confirmed. The direct mobile radio relay intentionally rejects HLS and Ogg until a phone-side transcoding layer exists; the desktop bridge remains the fully transcoding radio path.

## Architecture

```text
Local UPnP/DLNA player
            |
            v
 Android MediaRenderer facade
            |
            v
      local WAV proxy
            |
            v
  Samsung WAM control client
            |
            v
        Shape M5
```
