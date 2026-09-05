# Development status

Last reviewed: 2026-08-27.

Continuity note for playback work. Read this with `WAM_PROTOCOL.md` before reviving an old
branch or implementing another timing layer.

## Stable on `main`

- SSDP discovery with subnet fallback and saved devices resolved by stable device ID.
- Raw volume, mute, pause, play, stop and standby control. The `status` action reads
  `GetPowerStatus` best-effort, capped at one second: the command does not exist on this
  firmware and answers with silence, and until 2026-08-15 letting that timeout propagate
  made the whole snapshot fail and reported a healthy speaker as unreachable. `power=` is
  therefore always `unknown` here. The front LED is the visible indicator, but it is no longer
  the only one: `GetMute` tracks it — `on` while dark, `off` while lit — so the power state can
  be read without a person in the room. It reads the mute flag rather than the lamp, though, and
  this component mutes deliberately in places, so it is an idle-state detector rather than a
  check to run right after one of our own actions.
- Direct `SetUrlPlayback`, custom radio stations and native TuneIn preset playback.
- Windows builds for bundled helpers and foobar2000 2.x x64.
- Restricted helper handle inheritance from merged PR #2.
- Persistent event listener from merged PR #9.
- `Playback -> WAM Bridge` submenu with emergency stop, standby and physical volume actions
  from merged PR #23.
- PCM HTTP server keeps the first FFmpeg and refuses duplicate stream requests, so one
  encoder owns stdin.
- `cp` is documented as normal for the URL path; no URL startup gate or power-cycle advice
  may depend on that submode. That rule was broken once by a gate in `cli.py` and is now held
  by `tests/test_docs_match_code.py`. Re-measured 2026-08-19: an internet stream played
  audibly for its whole run with `GetFunc` reporting `cp`, and leaving `cp` is `SetFunc` to
  another source and back to `wifi` - not a power cycle. In `cp`, `GetPlayStatus` omits
  `playstatus` and `GetMusicInfo` answers `errCode: Wifi Sub Mode is CP`; neither means the
  playback failed.
- Speaker-facing output profiles `flac` (default), `wav`, `wav24` and `mp3`, selected by
  `format` in the INI or `--format` on the helper. Only `flac` has played a full track on
  hardware; `wav24` was accepted by the M5 on 2026-08-15 but dropped the stream twice at
  thirteen minutes. See `FOOBAR_PLUGIN.md` for what each one costs.
- A bounded helper restart loop from merged PR #55, measured on hardware. A spawn that never
  reaches `PLAYING` is charged and the next one waits 0.5 s, then 1 s, 2 s, 4 s, up to an 8 s
  ceiling; the budget is forgotten after a minute with nothing failing. A helper that reached
  `PLAYING` and then died restarts immediately, which is the recovery that works.
- An Android adapter under `mobile/`, exposing the speaker to phone players over UPnP.
  Physical Neutron playback to the M5 is confirmed. As of PR #97, renderer starts from the
  app, Quick Settings tile and widget all auto-resolve/discover the WAM target; the native
  TuneIn screen exposes station artwork/metadata plus play/pause, mute, raw-volume and Stop.

The stable universal transport is local HTTP started through `SetUrlPlayback`. The speaker
paces the HTTP side through TCP backpressure. Finite share/DLNA playback is proven as a
separate optional path but is not integrated into the foobar output.

## Merged: the foobar output clock (PR #21, `9b12d44`)

Merged 2026-08-02 after the full physical M5 checklist passed.

### What actually caused the runaway start

Not the clock. `process_samples` returns void, so a partial write cannot be reported and
the caller counts the whole chunk as delivered. The output took `min(free, chunk)` and
dropped the rest, so foobar advanced over audio that was never sent.

The per-second `CLOCK` line settled it in one run: `target` and `played` advanced at
exactly 1000 ms per second, `submitted` at about 1035 ms, `buffered` sat between 3.8 and
4.0 s of a 4.0 s capacity and `free` hovered near 100 ms — while foobar ran a 220 s track
out in 22 s. Every clock term was behaving. About nine tenths of each chunk was going in
the bin.

**Rule that follows: the void `process_samples` must accept every frame it is offered,
blocking until there is room.** Only give up when the stream is shutting down, flushing or
has been replaced. `process_samples_v2` keeps reporting partial writes; that is what its
return value is for.

After the fix, measured over a complete track: median 1.00x, 100% of samples between 0.9x
and 1.1x, natural transition into the next track, no leaked encoder.

Every hypothesis that preceded this — clock anchoring, refresh ordering, capacity —
described terms that measurement showed to be correct. None of them was the fault.

### What else the branch carries

- matched shared-socket responses: a rejected `SetUrlPlayback` fails the attempt and a
  rejected unmute fails startup, so `WAMBRIDGE PLAYING` cannot be printed over a speaker
  that was muted for startup and never unmuted,
- a `CLOCK` counter line behind `diagnostics=1`, in two phases: one a second for the first
  240 lines of each stream, then one every thirty seconds for as long as that stream lasts.
  It used to stop dead at 240 — four minutes in — which made it blind to anything that is
  not a startup problem, and a run that logged 240 healthy samples then went quiet read
  exactly like a healthy one,
- a write probe that prints its numbers again,

- counts queued, in-progress and submitted PCM in latency and capacity,
- starts one cumulative host clock at `WAMBRIDGE AUDIO_STARTED`,
- shifts that anchor only for pause and never re-anchors it to pipe-write completion,
- caps played frames by submitted frames,
- keeps `force_play()` as a transient drain request,
- keeps one TCP `55001` connection for commands and events,
- leaves `StartPlaybackEvent` diagnostic on URL/PCM instead of a 45-second hard gate,
- mirrors helper logs and errors into the foobar console,
- keeps FFmpeg free of `-re` and refuses duplicate stream encoders.

Physical measurements behind the design:

- gating capacity on `StartPlaybackEvent` filled the minimum four-second buffer and froze
  foobar after four seconds,
- releasing capacity from a clock repeatedly reset to `now` let foobar advance at about 94x,
- short `f32le -> FLAC -> M5` runs produced audible sound,
- the audibly playing URL path did not emit a matching start event before the old timeout,
- `NETWORK_TIMEOUT_ERROR` disappeared after stream starvation was fixed.

## Measured: about six seconds of audio delay

Re-measured 2026-08-08 on the physical M5 from the build of PR #41 (`78da8b2`, installed
and verified 69/69 by hash), with `startup_silence=0`. Method unchanged: the foobar volume
is changed through beefweb, the playback position at the moment of the change is recorded,
and the listener reads the position off the seekbar when the change is heard. Four changes
per run instead of two, over the same passages of the same 19-minute 44.1 kHz source, so
the two formats are compared on identical material.

| passage | FLAC | WAV |
|---|---|---|
| 192 s | 7.94 s | 5.97 s |
| 217 s | 5.91 s | 4.95 s |
| 242 s | 6.90 s | 5.91 s |
| 267 s | 5.91 s | 5.89 s |
| mean | **6.67 s** | **5.68 s** |
| spread | 2.0 s | 1.0 s |

**End-to-end delay is about 6.7 s on FLAC and 5.7 s on WAV.** The listener reads whole
seconds off the seekbar, so a single pair carries about a second of quantisation; the mean
difference of 1.0 s is at that limit and rests on the pattern - four passages out of four
in the same direction, and half the spread - rather than on the average alone.

Pause and resume agree from two other directions, same session: after a pause the sound
stopped about 5 s later, and after resuming from 51.98 s it came back at 58 s, so 6.0 s.
Three methods, one answer.

**The previously recorded 13.4 s is superseded.** It was measured 2026-08-02 on a much
older build with `startup_silence=1500`, from two data points, on unrecorded material.
The silence accounts for 1.5 s of the difference and nothing here accounts for the rest,
so treat the older figure as history rather than as a term to subtract from.

### The `wav` profile passed the physical checklist

Same session, same 19-minute source, sampled at 1 Hz with the process tree and socket table
beside beefweb. Every criterion in `AGENTS.md` measured rather than judged:

| criterion | result |
|---|---|
| track at wall-clock speed | 300 samples, `rate` median **0.999**, min 0.966, max 1.037, **100% within 0.9-1.1x** |
| stable seekbar | no excursion outside that band at any point |
| second track | index 3 to 4 seamless: 1140.69 s to 0.99 s, tempo back at once, **no restart** |
| seek | encoder retired and replaced in **under a second** while the helper's count never dropped; tempo back within ~2 s |
| pause/resume | 18 s paused, both sockets stayed `Established`, helper and FFmpeg alive, resumed from the same position |
| stop | 0 FFmpeg, 0 helper, no `Established` socket left |
| leaks | FFmpeg and helper never above 1; free RAM 2.2-2.4 GB throughout |

One honest gap: nobody was listening during these runs. The transport is proven; the absence
of audible artefacts is not. `flac` therefore stays the default and `wav` stays opt-in until
somebody has listened to a full track on it.

The 2026-08-27 `buffer_extra` sweep removed the unmeasured 2 s pad. At the new default
of 0, a three-minute M5 run logged 153 `CLOCK` samples with a 2.0 s capacity:
`buffered` 1.832-1.999 s (1.918 s average) and `free` 0-167 ms (81 ms average),
with no helper error or restart. The audible end-to-end total has not yet been re-measured.

| term | share | ours to change |
|---|---|---|
| host `buffered` | ~1.9 s | floored at 2.0 s by `clamp(bufferLength, 2.0, 30.0)` |
| `adelay` startup silence | 0 s by default; configurable up to 10 s | yes |
| FFmpeg and the HTTP socket | under a second | barely |
| the speaker itself | ~2 s on FLAC, less on WAV | partly, through bitrate |

Consequences, none of them optional to know:

- The host queue is no longer a four-second term; at about 1.9 s it is now in the same
  order as the speaker prebuffer. The remaining 2.0 s floor is the next buffer question.
- The volume slider applies a gain where PCM leaves the queue, and `queued` is 0-61 ms.
  Everything else is already past that point, so the slider cannot be responsive by
  construction. Route it to the speaker's own volume, which answers in about 1.3 s.
- Pause takes about 5 s to fall silent and resume about 6 s to come back, measured
  2026-08-08. Both are far above the 1.3 s a `55001` command costs, so routing them is still
  worth it - the prize is just five seconds rather than thirteen.
- **The prebuffer is partly bounded by bytes, confirmed twice.** A thinner stream is slower:
  `mp3` at 320 kbps measured 16.9 s against FLAC's 13.4 s on the old build. A fatter one is
  faster: `wav` at a constant 1411 kbps beat FLAC on every passage of the same source and
  halved the spread. The variance is the tell - FLAC's bitrate rides the material, so on an
  uneven mix the delay breathes with it, while WAV's constant rate does not. The gain is
  about a second, not the several the bitrate ratio alone would predict, which agrees with
  the mp3 ratio of 1.26x where 2.4x was expected: something else is bounded too.

## Closed investigations retained as evidence

### PR #4: manual PCM pacing

Closed without merge. It correctly proved that the M5 paces the speaker-facing HTTP stream
through TCP backpressure, but the original conclusion was too broad. Backpressure does not
pace foobar's own output accounting. Do not restore FFmpeg `-re` or HTTP throttling; fix
host latency and capacity instead.

### PR #7: finite share/DLNA playback

Closed without merge after the experiment became too large and its early assumptions were
invalidated. The useful result remains proven:

- `SetSharePlaybackControl` works,
- `device_udn` is the raw registered client UUID,
- media is served at `/DLNA/<objectid>` on port `49200`,
- `StartPlaybackEvent` confirms this path,
- finite playback can expose duration, pause and seek state.

A future implementation should be rebuilt as a small optional layer with one known-good
attempt, not resurrect the old fallback ladder.

## Current conclusions

### Universal URL/PCM transport

Status: validated on the physical M5. Pacing is correct; responsiveness is not.

- Works for files, radio and endless sources.
- Uses local HTTP without fake `Content-Length`.
- Uses one control connection and one encoder.
- Relies on speaker TCP backpressure for HTTP pacing.
- Requires separate bounded host accounting for accepted-but-not-heard PCM.
- Accepts every offered frame in `process_samples`, blocking until it fits. That entry point
  returns void, so a partial write is invisible and the caller counts the dropped remainder as
  played. Measured on the M5 (2026-08-02): foobar advanced 220 s of track in 22 s at a median
  11x while `submitted` grew at 1.04x, `buffered` sat at 3.8-4.0 s of a 4.0 s capacity and
  `free` hovered near 100 ms. The transport was never fast; the surplus was discarded.
- Matches shared-socket responses to the command that was sent. A matched `ng` fails startup
  for `SetUrlPlayback` and for the `SetVolume` that undoes the startup mute; an unanswered
  command still counts as success, and unmatched bodies stay diagnostics.

### Finite share/DLNA transport

Status: protocol proven, product integration deferred.

Use it later for local files that benefit from native duration, pause and seek. It cannot be
the universal foundation because it does not cover endless sources.

### Generic UPnP AVTransport

Status: rejected for the tested `SPK-WAM550`. The service is not exposed.

## Settled, do not re-litigate

Each of these cost real time and each is closed by measurement, not by argument.

| Question | Answer | Where |
|---|---|---|
| What caused the runaway start | `process_samples` returns void; taking `min(free, chunk)` binned ~9/10 of every chunk | this file, above |
| Does a lower bitrate shorten the delay | **No, it lengthens it.** MP3 320k = 16.9 s against FLAC's 13.4 s; and a fatter WAV beat FLAC on every passage | `WAM_PROTOCOL.md` |
| Is `cp` submode a fault | No. It is the normal submode for `SetUrlPlayback` and also the idle one | `WAM_PROTOCOL.md` |
| Does the SDK offer a hardware volume interface | `output_entry_v2::get_volume_control` exists but no public component implements it; not a foundation to build on | PR #30 |
| Does `flag_needs_shims` affect volume | No. It means regular `update()` calls and end-of-stream padding | SDK `output.h` |
| Can a command clear `cp` | Not observed. `SetPlaybackControl stop` is accepted and does not clear it | `WAM_PROTOCOL.md` |
| Does the M5 auto-power-down | **Yes, but only once every program lets go.** Then it goes dark after under 17 minutes idle. A session nobody ended keeps it lit indefinitely: measured 2026-08-16, 33 minutes and still lit after a foobar shutdown on a build that sent no release, against 17 min 4 s to dark on one that did. The interval itself has no knob - `GetPowerSaving` and `GetAutoPowerDown` do not exist - so `SetSleepTimer` (seconds) remains the only way to reach standby *on demand* | `WAM_PROTOCOL.md` |
| Does closing the stream end playback for the speaker | It does **since PR #48**. Before that nothing on the PCM path ever told it, so every session left a URL session whose source had vanished - and that is exactly what stopped the idle countdown from starting | `WAM_PROTOCOL.md` |
| How to tell whether the LED is lit | `GetMute`: `on` = dark, `off` = lit. Read-only commands do not wake a dark speaker; whether they reset the idle countdown on a lit one is unknown, so measure with one late reading, not a poll loop. Caveat: this component **also mutes deliberately** - `standby` sends `set_mute(True)` - so `mute=on` proves dark only when nothing has just muted it | `WAM_PROTOCOL.md` |
| Do hard-killed sessions wedge the speaker | No. 78 killed helpers left 29 sockets in `TIME_WAIT`, then one normal stop and it went dark unaided within ten minutes | `WAM_PROTOCOL.md` |

## Measured: the helper restart loop, and why a counter could not stop it

Killing one `wambridge-pcm.exe` while foobar played produced **77 restarts in 90 seconds**,
about one a second, and after the killing stopped the loop carried on by itself at one death
every 25 seconds (2026-08-16). No FFmpeg process ever appeared - the helper died before
encoding. Only stopping playback in foobar ended it.

The damage was not confined to this side. Each death left a socket in `TIME_WAIT` to port
55001, 29 of them at the peak, and at that point **the speaker stopped answering commands
altogether** for the first time we have seen; it came back slower than usual, 0.14-0.26 s
against the normal 0.02-0.12 s. The front LED was still lit four minutes after the last death.

What kept it going is worth stating plainly, because it defeats the obvious fix: reporting a
failure throws `exception_output_invalidated`, and foobar answers that by **constructing a new
output object**. A retry counter held in that object is therefore zero again on every attempt,
which is why the retries never slowed down. The budget has to outlive the rebuild, so it lives
at file scope.

The discriminator is `PLAYING`. A helper that reached it and then exited is the speaker ending
a stream, and restarting immediately is the recovery that works - about two and a half seconds
of silence and audio is back. Only spawns that never got there are counted, and each one pushes
the next attempt further out: 0.5 s, 1 s, 2 s, 4 s, up to an 8 s ceiling, with the whole budget
forgotten after a minute in which nothing failed.

**Where the count is taken decided whether any of this worked, and two placements failed on
hardware before the third held.** Both failures share a shape worth remembering: the accounting
leaned on state that the failure itself resets.

- *At the point of death.* A dead helper surfaces in three places, and the protocol reader
  usually wins the race - it sets `m_failure` and the worker never reaches the `exited` branch
  that held the counter. The brake never engaged at all.
- *At a verdict settled later.* A flag armed on spawn, cleared by `PLAYING`, charged by the next
  `start_child`, with deliberate teardowns exempted. Two separate leaks: `flush()` clears
  `m_failure` and raises `m_restart`, and foobar calls it after a failure is reported and before
  it destroys the output object, so every "this teardown was deliberate" test fired on the
  failure path; and `flush()` also calls `retire_stream_locked()`, which bumps the generation and
  zeroes the format, so the attempt holding the pending verdict was routinely abandoned and took
  the charge with it. Measured: relaunches 0.76, 0.73, 0.74, 1.25, 1.23 s apart where the
  schedule calls for 0.5, 1, 2, 4, 8 - roughly two charges in three lost.
- *At the spawn.* Charged the moment `CreateProcessW` succeeds, refunded by `PLAYING`. Nothing
  has to survive in between, so nothing can erase it.

Measured on the physical M5 on 2026-08-19, killing each helper before it could play: gaps of
0.5, 0.5, 1, 2 and 4 s, doubling as designed. Killing a helper that *had* played gave a restart
0.22 s later, so stream-end recovery is untouched. Beware the measurement itself: a kill loop
that timestamps its own `Stop-Process` calls reports its own polling period - the first run
showed a flat 0.33 s that was the loop, not the component. Use the process `StartTime` the
operating system records.

## Open, in the order that makes sense

1. ~~**Physical checklist for PR #30**~~ (routed volume slider). PR #30 is closed; the routed
   slider is in `Stable on main` above. Struck 2026-08-19 during a claim-by-claim audit.
2. ~~**Find how small the extra host buffer can get.**~~ **Done 2026-08-27.** The physical
   M5 passed 1500, 1000, 500 and 0 ms without a transport sign of starvation. At 0, a
   three-minute run held the 2.0 s capacity 1.832-1.999 s full across 153 `CLOCK` samples,
   then a ~24-minute Andor session stayed stable and sounded clean to the owner. The full
   checklist also passed, driven through the local authenticated Beefweb API and checked in
   foobar's console-*.txt: pause/resume froze and resumed the same clock, seek and manual
   next restarted cleanly with one helper/FFmpeg pair, a complete 3:23 track transitioned
   naturally into the next track without restarting the transport, and Stop left no helper
   or FFmpeg behind. `buffer_extra` now defaults to 0; the remaining 2.0 s
   `clamp(bufferLength, 2.0, 30.0)` floor is a separate experiment.
3. ~~**Decide whether `startup_silence` should default to 0.**~~ **Done 2026-08-27.** The
   default is now 0 in both the helper and foobar settings. Hardware had already passed a full
   session at 0; a fresh short check on the physical M5 also held the seekbar at 1.00x and
   stopped with no helper or FFmpeg left behind.
4. ~~**Route pause onto `55001`.**~~ **Done and hardware-validated 2026-08-28 in PR #112.** Two
   tempting speaker controls were rejected first: UIC `SetPlaybackControl pause` answers cleanly on
   URL/PCM but does nothing, while `SetMute` silences promptly but closes the M5 HTTP pull and can
   hand the speaker back muted. The accepted route uses raw speaker volume on the helper's existing
   `55001` connection: pause stores the active level, writes 0, keeps paced PCM silence flowing, and
   resume/teardown restores the saved or externally updated level. `VolumeLevel` broadcasts refresh
   that target; a nonzero external change during pause is immediately countered with raw 0 while
   remaining the resume target, and the helper ignores its own pause-generated zero for restore
   purposes. A matched teardown restore rejection is retried once and then reported as
   `restore=rejected` rather than being hidden behind an otherwise clean Stop.

   The final artifact `75daa1a` passed the physical M5 checklist through authenticated Beefweb:
   an external raw-volume change to 3 survived a **40 s pause** at 0 and resumed to exactly 3 with
   the same helper and FFmpeg PIDs and no timeout; Stop during pause restored 3, left `muted=off`,
   `holding=0`, and 0 helper/FFmpeg processes; Next during pause cleanly retired Starburster and
   started Andor at volume 3 with one fresh helper/FFmpeg pair. Final Stop again ended at volume 3,
   `muted=off`, `holding=0`, with foobar closed and no helper/FFmpeg left. The old ~5 s pause / ~6 s
   resume figures remain historical measurements of the PCM-only path; routed audible latency was
   not re-timed acoustically in this pass.
5. ~~**Stop the helper respawn storm.**~~ **Merged and measured 2026-08-19**, PR #55. The
   backoff is charged at the spawn and refunded by `PLAYING`; see the section above for the two
   placements that failed first and why. Nothing is left open here.
6. ~~**Rename the misnamed standby menu item.**~~ **Done 2026-08-29.** The foobar menu now
   says `Stop & mute`, which is exactly what the existing action does: it stops playback and
   mutes the speaker without claiming to enter network standby. The legacy helper action name
   remains `standby` for compatibility. A real on-demand sleep control stays separate as item 7.
7. ~~**Offer the sleep timer as a menu command, not only as an automatic fallback.**~~ **Done and hardware-validated 2026-08-29.**
   `Playback -> WAM Bridge` now exposes `Start sleep timer` and `Cancel sleep timer`, reusing
   the configured `sleep_after_stop` duration. During PCM playback the command travels over
   the helper's existing control connection. The absolute deadline survives helper replacement
   and foobar restart, and release/discard preserves a menu-armed timer. Foobar stops two seconds
   before the M5 deadline so standby does not look like a failed helper exit and respawn it. A
   30 s physical M5 pass confirmed the timer fired with no helper respawn. Persistence is
   best-effort: if the INI write fails, the live coordinator still follows the timer accepted
   by the speaker.

8. ~~**Move release onto the helper's control channel.**~~ **Done.** The component now
   sends `release` (a real stop) or `discard` (replacing this helper for a seek or format
   change) over the existing `WAMBRIDGE CONTROL_PORT` channel, from `~WamOutput()`,
   `flush()` and `submit_chunk()`'s format-change branch respectively - before it starts
   killing the helper, while the control socket is still open. `PlaybackWatcher.release()`
   and the new `discard()` (release minus arming the sleep timer only) share one body,
   idempotent under a lock so a concurrent call from the control channel's dispatch thread
   and the helper's own unconditional exit-path fallback cannot both run the teardown.

   Corrected while landing this: the fix does not make the helper survive an encoder that
   never exits *unconditionally* - `kActiveShutdownGraceMs` (6 s) still ends in
   `TerminateProcess` regardless. What changes is that a real stop no longer *waits on* the
   encoder reaching that point before telling the speaker to stop: `release` reaches the
   speaker immediately over the still-open control socket, decoupled from whether `_run`
   or the FFmpeg pipe it reads from ever return.

   `discard` closes the race `cancel_sleep_timer()` exists to patch after the fact (a
   replacement helper clearing a timer the *old* helper's exit just armed) rather than
   removing the need for it - a timer armed by an older build or the Samsung app itself is
   still possible, so `cancel_sleep_timer()` / `--clear-sleep-timer` stay as defense in
   depth. `discard` still runs the stop and the paused-volume restore, only the sleep timer
   is skipped: `flush()` can run before the component has fully committed to ending the
   process rather than replacing it, and skipping the stop entirely there would risk the
   exact "still lit the next morning" failure `release()` exists to prevent.
9. ~~**Tighten the window on a released speaker going dark.**~~ **Answered 2026-08-16.** A
   session ended `WAMBRIDGE STOPPED stop=sent sleep=off holding=0` at 20:57:15 and `GetMute`
   reported dark at 21:14:19: **17 min 4 s**, with no timer armed. The controlled comparison
   is what makes it conclusive rather than suggestive - the same speaker the same evening, on
   a build that sent no release, was still lit 33 minutes after its last audio. So the idle
   interval is roughly a quarter of an hour and it starts when the last program lets go, not
   when the audio stops.
10. Reduce and reimplement the finite share path from its measured working form.
11. ~~Add a proper foobar preferences page while retaining legacy INI compatibility.~~
    **Done** - `foobar/wam_preferences.cpp` implements `preferences_page_instance` in 524
    lines, and the INI keys still load. Struck 2026-08-19 during a claim-by-claim audit; it had
    been false since the page landed.
12. Extend the radio side. **This item was wrong and stayed wrong for a while:** it claimed
    nothing lists what the speaker holds and a preset can only be recalled by a known number.
    `wambridge --tunein-list` has listed them all along, paginated and with the service
    selected first, in `src/wambridge/tunein.py`. Two things are genuinely missing, both now
    with commands attached in `WAM_PROTOCOL.md` and neither tried on hardware:
    **writing presets** (`SetSavePreset`, `SetRemovePreset`, `SetMovePreset`, against a README
    that still says no write API is known) and **browsing the catalogue** for stations not
    already saved (`GetUpperRadioList`, `GetCurrentRadioList`, `SetSelectRadio`,
    `GetGenreStations`, `SearchQuery`). A dockable panel still waits on output transport.

    Order agreed for the mobile side, largest gain first: ~~read-only browsing
    (`GetUpperRadioList`, `GetCurrentRadioList`), then search, then a browsing UI once the
    structure is understood~~, and only then writing or removing presets. That is a bigger step
    than further cosmetic work on the renderer.

    **The read-only three-quarters of that order are done and hardware-validated 2026-08-28.**
    `CatalogueActivity`, reached from `MainActivity`, walks the tree, pages through long lists
    and searches, and plays a station through the relay. Driven on the physical M5 over adb:
    root `Browse — 12 of 12`, `Local Radio — 30 of 90` needing **two** *Load more* taps before
    the button went away, a search followed by *Up* landing back on the root rather than
    stranding in the search tree, and audible playback from `Favorites 12 of 12`. That last
    step is also a third independent confirmation that the relayed path reports
    `play_status=stop` with an empty `title` while audio is really flowing.

    **What remains under this item is the write side only**: `SetSavePreset`,
    `SetRemovePreset` and `SetMovePreset`, none of them yet tried on hardware, so a station
    found by browsing still cannot be kept. That is the same write-side probing as item 14,
    and the browsing screen is the natural place to call it from.

    ~~**Open, small, and asked for while testing on 2026-08-19: the mobile radio screen has no
    stop button.**~~ **Wired up 2026-08-19 and refined from the 2026-08-20 measurements.**
    `TuneInActivity` now has a confirmed Stop control. The measured silent source detour is
    `SetFunc aux`, a two-second pause, then `SetFunc wifi`; `bt` was retired because the speaker
    announces Bluetooth readiness aloud. Neither `SetPlaybackControl stop` variant moves the
    native TuneIn stream - see the `cp` section of `WAM_PROTOCOL.md`. Because the SetFunc sends
    are fire-and-forget, the screen polls `GetFunc` and reports the returned `function` and
    `submode` rather than assuming the stop worked.

    ~~**Next concrete step, agreed 2026-08-19: a TuneIn id in the station store.**~~
    **Done on desktop and mobile by 2026-08-20.** Saved stations can carry an id such as
    `s15984`; it is resolved at play time so a broadcaster can move its endpoint without
    invalidating the station. The resolved HTTP stream is tried before the saved static URLs,
    while timeouts, empty/HLS-only answers and failed resolution fall through to those saved
    URLs. Mobile also expands TuneIn `.pls` answers before handing candidates to the relay.

    Both halves are measured. `s15984` resolves to `http://stream3.polskieradio.pl:8954/`, and
    the `czworka` pack entry plays on its primary `http://stream3.polskieradio.pl:8906/;stream`
    and on its fallback `http://mp3.polskieradio.pl:8956/;` alike, both confirmed by ear.
    Note the resolution has to happen on the PC: the speaker cannot fetch `Tune.ashx` itself in
    any useful way, and the resolved URL is what `SetUrlPlayback` receives.

    **Browsing is measured as of 2026-08-19** and the first rung is done: the catalogue is a
    tree, descended with `GetSelectRadioList` and a `contentid`, and `GetStationData` hands
    back a `stationurl`. **That URL is not playable** - it is a `Tune.ashx` playlist and
    `SetUrlPlayback` refuses it, corrected 2026-08-28 after this paragraph had claimed the
    opposite since 19.08. Writing presets may still never be needed, but the route is browse,
    resolve the `mediaid`, relay. Browsing also costs nothing: it does not move the submode, a
    claim this file and `WAM_PROTOCOL.md` briefly carried in the opposite direction. What is
    **Search is measured as of 2026-08-20** and works: `SearchQuery` is paginated and returned
    66 hits for `Trojka`, with the owner's own preset station `s15984` among them. So finding a
    station by name needs neither the browse tree nor a preset write. `GetGenreStations` refuses
    cleanly with "API not implemented for current service"; `GlobalSearch` answers but returns
    nothing on this speaker - it searches signed-in content providers and there are none, so the
    `str_arr` support it would need is not worth adding. What is left is the UI.

    **Browsing and search stopped being probe-only on 2026-08-27.** They now live in
    `src/wambridge/catalogue.py` with a unit suite over redacted real answers, and the CLI
    exposes them as `--tunein-browse [PATH]`, `--tunein-search QUERY` and `--tunein-start N`.
    The module carries the three firmware traps the probes turned up: a search leaves the
    cursor in a second tree whose root is `Search` and which also reports `isroot="1"` (only
    `BrowseMain` crosses back, and `open_catalogue` sends it automatically); `contentid` is a
    page-local index while `mediaid` is the stable id; and a `totallistcount=0` on a level that
    has content means the CPM subsystem is recovering, so an empty page is retried before it is
    believed. Verified against the physical M5 the same day. Still left is the UI, desktop and
    mobile alike.

    **What is left is more than the UI, measured 2026-08-28.** The step from a browsed station to
    audio does not work the way `WAM_PROTOCOL.md` said it did: the `stationurl` from
    `GetStationData` is a `Tune.ashx` playlist, not a stream, and `SetUrlPlayback` refuses it with
    `ErrorEvent` `ng`. Resolving the playlist client-side is half of it; the resolved URL then
    drew no answer of any kind, which read as a second protocol puzzle at the time and was not
    one - the speaker was wedged, see `GetStationData` in the protocol file. So a browsed station
    should reach the speaker the way every other radio here does - resolved, then relayed from
    the client. Confirmed end to end on the physical M5, 2026-08-28, on a station taken straight
    out of the speaker's own listing: `SetUrlPlayback` accepted, and the speaker held a
    connection back to the relay port while it played.

    The concrete want behind this is the **physical Radio button**, which cycles the three
    presets of kind `speaker` - today `PR3 Trójka`, `Czwórka` and `BBC Radio 1`. They are
    already readable with `--tunein-list`; what is missing is putting a different station
    there without Samsung's app. `WAM_PROTOCOL.md` has the three write commands, the shape of
    `SetSavePreset` (no arguments - it stores whatever is selected), and an ordered way to
    find out whether `SetMovePreset` can simply promote a `my` entry into slots 0-2.
13. ~~**Take the Samsung Android app apart.**~~ **Done 2026-08-19.** 242 commands recovered
    and written up in `WAM_PROTOCOL.md`; 26 of them were already in use here. What follows from
    it is spread across items 7, 12 and the new item 15 rather than kept as one lump.
    Unresolved side question: the mirrored APKs are signed with a self-signed personal key, not
    a Samsung corporate one, and two independent mirrors agree on the fingerprint.
14. **Probe the recovered commands against the physical M5, cheapest first.** They are
    inferred from a decompiled app, so each one is a hypothesis until the speaker answers.
    First round done 2026-08-19: `GetNetworkStandByMode`, `GetCpList`, `GetPresetList` and
    `GetRadioInfo` all answered and are written up. Second round the same day: the whole rung-1
    read sweep plus the radio tree, by `tools/wam-probes/probe_rung1_reads.py` and
    `probe_radio_browse.py`. Silent on this firmware: `GetWooferLevel`, `GetRearLevel`,
    `GetAudioQuality`, `SpkInGroup`. The read-only follow-up is also done: `SearchQuery`
    works and `GetGenreStations` cleanly reports that the API is not implemented for the
    current service. Anything further here is now write-side probing, not another read sweep.

    **Settled 2026-08-25, and neither side of the old question was right.** `SetPlayPreset`
    works; `--tunein-play "Radio Paradise (Alternative Rock)"` printed `Playing TuneIn preset
    10` after `_wait_for_tunein_playback` confirmed it, and presets 2, 3 and 10 all started on
    the physical M5. What made it look broken was the observation method:

    - **`StopPlaybackEvent` is the acknowledgement `SetPlayPreset` always returns**, including
      on the calls that then played. It reports that the previous playback was torn down, not
      that the new one failed. Reading it as an error is where "nothing plays" came from.
    - **Start latency is 2-5 s and varies for the same preset** - measured 2.2 s and 4.5 s on
      two consecutive attempts at preset 3. Any fixed short check returns contradictory
      results, which is exactly what two sweeps here did before the latency was measured.
      `GetRadioInfo` polled to `playstatus=play` is the only honest test, and
      `radio_cli._wait_for_tunein_playback` already does it with a 25 s budget.

    Two arguments were suspected and both are correct as they stand: `contentid` in
    `GetPresetList` **is** the list position 0..N-1, so `WamPreset.preset_index` returning
    `int(content_id)` is right, and the `kind`-to-`presettype` mapping (`speaker` 1, `my` 0)
    matches what the speaker accepts. `signinstatus` is `0` and the `my` presets still play, so
    playback needs no signed-in account.

    What does not play is **preset 0 specifically**, on every attempt - which confirms the
    suspicion already recorded in `WAM_PROTOCOL.md`: its station is Trójka `s15984`, whose
    resolved address answers `200 text/html` rather than audio. That is a broken station, not a
    broken command.
    Record each as measured or as silent in `WAM_PROTOCOL.md`, the same way the rest of that
    file distinguishes them - a command that exists in the app and dies on this firmware is
    worth writing down exactly once.
15. Finish the mobile adapter's own list. The renderer path now has automatic discovery from
    every start surface, an AVTransport facade, WAV/LPCM/MP3/FLAC proxying, a Quick Settings
    tile, compact and expanded widgets, native TuneIn controls with artwork, and saved direct
    radio stations.

    **Extended on 2026-08-27:** Android now consumes the same bundled
    `src/wambridge/station_packs.json` as the desktop CLI, so the radio screen is useful on a
    fresh install instead of opening empty. User edits override bundled entries and deletions stay
    deleted. The mobile list currently exposes 14 relay-compatible favorites with their ordered
    URL fallbacks and TuneIn ids; the three HLS/Ogg-only entries (`bbc1`, `bbc6`, `falloutfm5`)
    remain desktop-only until the phone has a transcoding path.

    Renderer startup is also an explicit `STARTING/RUNNING/STOPPING/STOPPED` lifecycle now.
    Discovery can be cancelled by Stop, saved speakers are re-found by stable `GetDeviceId` after
    DHCP moves them, and the UPnP facade sends SSDP `alive`/`byebye` announcements instead of
    relying only on a control point to issue a fresh `M-SEARCH`.

    ~~The renderer serves its stream to any host on the Wi-Fi that guesses the per-session
    path.~~ **False, and it had been false for six days when this file repeated it on
    2026-08-25.** `ef2d273` ("Harden Android mobile adapter", 2026-08-19) put two rules on the
    HTTP port: the stream path answers the speaker's address and nothing else, and every other
    path answers this phone and nothing else. Re-read against the code on 2026-08-26. The claim
    survived because it was carried forward by hand instead of re-checked.

    What was genuinely missing is that nothing tested those two rules. They lived inside
    `handleClient`, tangled with sockets, so no unit test could reach them.
    `RendererRouting.route` is now a pure function of (method, path, peer) and
    `RendererRoutingTest` pins the order that carries the property: the stream to the speaker
    only - not even to the phone, which is the source and never a consumer - everything else to
    the phone only, and a stranger refused before the path is looked at, so an unknown path
    cannot be used to map the surface.

    ~~Left open here: the richer TuneIn catalogue/search UI from item 12.~~ **Shipped
    2026-08-28** — see item 12 for what the screen does and what it was measured doing. The
    first run on a phone also found a bug no unit test could: `parseXml` asked for
    `disallow-doctype-decl` outside its `try`, Android's `DocumentBuilderFactory` does not
    implement it, and the resulting `ParserConfigurationException` message was what the screen
    displayed instead of a catalogue. The desktop JVM implements it, so 53 green tests over the
    same parser said nothing. Hardening features are now requested one at a time and a refusal
    is tolerated (PR #121).

    The LAN-scan half is done. It never went quiet, which is what this item used to claim: past
    `MAX_SCAN_HOSTS` (1024 usable, so anything wider than a /22) `subnetPlan` narrows to the /24
    window around the phone rather than spraying a /16. What was wrong is what it then said —
    an empty result reported "No WAM speaker found. Enter the IP manually if discovery is blocked
    by the network", which sends the reader after a firewall when the app had checked 254 of
    65 534 addresses. `discover` now returns the coverage alongside the speakers
    (`Scan.Full` / `Narrowed` / `NoAddresses` / `NotRun`), and only a full sweep is allowed to
    say "not found".

16. **Get the speaker out of the three states it can be abandoned in.** All three come from
    the same asymmetry: on the relayed path the speaker holds a session that only this side
    can end, so anything that kills this side without a `stop` strands it. Nothing here is
    hypothetical - two were measured, the third is documented above.

    - *The source vanished mid-stream.* A PC that loses power, a helper killed outright.
      The speaker falls silent, keeps the session, and **never idles** - the standby
      countdown only starts once every program has let go (`WAM_PROTOCOL.md`, measured
      2026-08-16: 33 minutes still lit after a session that sent no release, against 17
      minutes to dark after one that did). Recovery is one command, `emergency-stop` or
      `standby`; nothing sent it because the side that would is gone.

      **Coded and unit-tested 2026-08-30. The same-speaker path is now
      hardware-validated too (2026-08-30).** `arm()` writes a small lease file
      (`src/wambridge/lease.py`) naming the speaker and this process's pid before
      offering the stream; `_release()` removes it once teardown confirms the speaker
      is actually clear (`stop=sent`/`stop=skipped`) - not on
      `stop=unreachable`/`stop=rejected`, where the abandoned session may still be held.
      A background thread on every new PCM session, started right after its own
      `probe()` succeeds, sweeps the lease directory: a stale lease naming *this*
      session's own target is skipped, not deleted (`run()` calls
      `discard_superseded` for it once `offer_stream()` has actually succeeded - a
      startup that fails first must not lose the only record of the abandoned
      speaker over a promise that was never kept, found in review), and every
      other stale lease is claimed (renamed so a concurrent sweep skips it),
      re-checked with `has_live_lease` in case some other session has since taken
      it over, and sent `standby(require_stop_confirmed=True)` - stricter than the
      interactive command, since nothing here can notice a wrong "recovered". A
      claim `standby` cannot yet resolve is left in place; a claim past a 60 s
      timeout - own recovering process died, or the attempt failed - is retried by a
      later sweep.

      On the physical M5: `wambridge-pcm.exe` killed outright while playing, foobar's
      own output-invalidation path relaunched a fresh session against the *same*
      speaker within about a second, and the crashed session's lease was found
      deleted outright (not renamed to a `.recovering-*` claim) with no `standby` or
      audible interruption sent in between - the discard branch, exactly as designed.
      Still open: the *different*-speaker claim-and-`standby` branch has no physical
      coverage, since exercising it needs a second WAM speaker abandoned by a crashed
      session while a new session targets a different one - out of reach with the
      single physical M5 this project has. Unit-tested with mocks only.

      Known limitations, accepted rather than fixed:
      - Recovery trusts a lease's `(ip, port)` without re-verifying the speaker's
        identity, so a DHCP reassignment could in theory point `standby` at a
        different device that took over the address. Low-probability on this
        project's single-physical-M5 scope, and the identity check available
        (`samsung.identify`) costs an extra round trip on every session start to
        guard an edge case - a worse trade against this project's documented
        startup-latency sensitivity than leaving it noted here.
      - A lease is trusted alive purely by its pid existing; an ordinary pid-reuse
        window (reboot, or the next session recycling the same number) can read an
        abandoned lease as live, and a helper that reuses a crashed one's pid
        overwrites its file outright. Distinguishing a reused pid from its
        original owner needs a process-creation-time check this module does not
        make; the failure mode is a missed or delayed recovery, not a wrong
        `standby`, so it is left as a documented gap rather than fixed here.
      - `has_live_lease`'s recheck before `standby` narrows, but does not close, a
        race between two sessions recovering two different abandoned speakers at
        once: a session whose own `arm()` lands in the gap between that check and
        the `standby` call is still exposed. Closing it fully needs a lock shared
        across processes, out of proportion to a scenario that needs two speakers
        crashing close together, on a project with one physical M5 to test either
        half of it against.
    - ~~*The phone walked out of Wi-Fi.* Same end state, plus a second wrong one: nothing in
      `RadioService` watches for the network going away, so the foreground notification goes
      on claiming playback that stopped minutes ago, and coming back into range recovers
      neither side.~~ **Fixed in PR #146 (2026-09-05).** Android now treats the selected
      `Network` handle plus IPv4 address as one endpoint identity. Renderer/radio local HTTP
      listeners and WAM control reuse that same target; the radio's outbound internet-source
      fetch still selects from the currently available Wi-Fi targets independently. Endpoint
      movement rebuilds the renderer and reconnects radio, while temporary loss leaves the foreground
      lifecycle waiting and retries when Wi-Fi returns. Radio recovery preserves the requested
      station, pause/mute/target-volume state and playback ownership, uses bounded exponential
      retry once an endpoint exists, and ignores callbacks from retired proxy instances.

      The implementation is deliberately SSID/frequency agnostic: a 2.4 → 5 GHz move matters
      when Android presents a new `Network`/IPv4 endpoint, not because the app parses a band
      name. JVM coverage pins endpoint classification and radio recovery ownership/backoff.
      Physical Xiaomi + M5 testing also caught and fixed a real `RadioProxyServer` crash caused
      by the local reachability preflight connecting and closing before HTTP headers. A complete
      post-handoff playback observation was not obtained in that session because wireless ADB
      disappeared after the phone switched networks, so do not turn that run into a stronger
      hardware claim than it was.
    - *The playback path wedged.* Measured 2026-08-28: after a `SetUrlPlayback` aimed at a
      URL the speaker cannot pull, `SetUrlPlayback` and `SetStopPlayback` stop answering
      entirely - the full timeout, no reply - while `GetFunc`, `GetVolume` and `SetVolume`
      keep answering in 0.1 s. **Only a power cycle clears this one**, so the commands that
      would recover the first two states are exactly the ones unavailable here. ~~Worth a
      cheap guard on the way in: refuse to hand the speaker a URL that has not been shown
      to be fetchable.~~ **Guarded 2026-08-28.** `assert_stream_reachable` in `samsung.py`
      and its twin in `SamsungWamChannel.kt` run on every path that sends the command -
      `play_url`, the PCM session's `offer_stream`, and the phone's `offerStream` - and
      refuse a URL whose host and port accept no connection.

      Two deliberate limits, both about the same fact. It **connects and closes without
      sending a request**, because every URL offered here is served by our own local HTTP
      server and those serve one consumer: a probe that asked for the body would be the
      second, which `AGENTS.md` already lists among the traps that have broken a working
      stream. And it is therefore a check on the address, not on the audio - a server that
      accepts the connection and then answers 404 still gets through. That is the right
      trade while nothing hands the speaker a remote URL: the desktop relays radio through
      FFmpeg and both mobile call sites offer the phone's own proxy, so a dead port is the
      failure that actually happens, and it is the one that cost two power cycles.

    Presets are the counter-example that shows what "abandoned" is really about: they play
    from the speaker's own connection to the internet, so none of this applies to them.
    Verified 2026-08-28 - a preset kept playing with no established TCP connection between
    this machine and the speaker at all.

17. ~~**Bring `catalogue.py` back in step with its mobile twin.**~~ **Done 2026-08-28**, the
    same day the two fell out of step. `RadioEntry.is_station` now requires the `s…` station
    shape, because a podcast episode is `type="2"` as well and carries a `t…` id nothing here
    can resolve - measured by descending into a programme found through a search, 50 of them
    in one level. And `open_catalogue` no longer breaks out of its retry loop on `is_root`
    alone: a recovering CPM answering `totallistcount=0` used to yield an empty catalogue root
    that the caller believed, and the root is never genuinely empty, so that case keeps
    retrying like `_fetch_page` does. Neither had bitten on the desktop, which was the point
    of doing it before they drifted further.

## What the 7-8 s speaker figure was

A subtraction remainder, not a measurement: total delay minus the terms that could be
counted, so it absorbed every error in the others. The 2026-08-08 re-measurement retired it.
With the whole path down to 6.7 s and the host's own buffer accounting for about 3.9 s of
that, there is no seven-second speaker to point at.

The owner's report that Samsung's own PC and Android clients felt clearly faster than 13 s
on this same speaker turned out to be the right instinct: most of the difference was ours,
not the speaker's. Do not reintroduce a large fixed speaker term into any budget without
measuring it directly.

## Rules for continuing

- Check `main`, open PRs and recent commits first.
- One logical stage per PR; no unrelated refactors or long changelogs.
- Do not merge transport work without the physical M5 checklist.
- Do not reopen AVTransport without new device evidence.
- Do not reintroduce `-re`, socket throttling, fake `Content-Length`, competing 55001
  listeners or multiple FFmpeg readers for one PCM stdin.
- Keep raw test volume at step `3` or lower.
