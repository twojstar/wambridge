# Testing Patterns

## 1) Test Stack and Commands
Python uses stdlib `unittest` plus `unittest.mock`; Android local tests use JUnit 4. CI runs `uv run python -m unittest discover -s tests -v` and, separately, `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`. C++ has no native unit-test runner in the repo; it is compiled in CI and many invariants are pinned by Python source-shape tests.

## 2) Test Layout
There are 36 Python `tests/test_*.py` files and 11 Android JVM test files under `mobile/app/src/test/java/...`. Tests are mostly feature-oriented (`test_samsung.py`, `test_pcm_stream.py`, `RendererRoutingTest.kt`). No shared global test setup file is required.

## 3) Test Scope Matrix

| Scope | Covered? | Typical target | Notes |
|---|---|---|---|
| Unit | yes | parsers, validation, state/routing, FFmpeg command construction | network/process calls usually mocked |
| Cross-file contract | yes | C++ source invariants, docs-vs-code, shared station formats | Python reads tracked sources directly |
| Build integration | yes | PyInstaller helpers, C++ DLL/component, Android APK | GitHub Actions |
| Real speaker | manual/probe | firmware timing, CPM quirks, audible behavior | physical M5 is required |
| Full automated E2E | no | real foobar/phone → physical speaker | hardware/state makes it unsuitable for ordinary CI |

## 4) Mocking and Isolation Strategy
Python uses `patch`, in-memory streams and fake process/socket objects so protocol and timing logic can be asserted without touching the M5. Android tests isolate pure routing/parsing/network-planning helpers and use local `ServerSocket` only where reachability itself is the behavior. Hardware probes are deliberately outside the supported test suite.

## 5) Coverage and Quality Signals
No coverage percentage or threshold is configured. Quality signals are green unit/build/lint jobs plus explicit regression tests derived from observed failures. High-value examples include XML/request-injection validation, bounded startup reads, single-owner PCM encoding, dead-stream refusal, LAN route restrictions, and docs/code drift tests.

## 6) Evidence
- `.github/workflows/build.yml`
- `.github/workflows/mobile.yml`
- `tests/test_samsung.py`
- `tests/test_pcm_stream.py`
- `tests/test_foobar_source.py`
- `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/RendererRoutingTest.kt`
- `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/WifiLanEndpointTest.kt` pins Android `Network` + IPv4 endpoint transitions, including same-address handoffs.
- `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/RadioRecoveryPolicyTest.kt` pins radio ownership during recovery plus retry limits/backoff.
