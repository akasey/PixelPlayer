# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Phone app
./gradlew :app:assembleDebug                          # debug APK (with ABI splits by default)
./gradlew :app:assembleDebug -Ppixelplay.enableAbiSplits=false  # single universal debug APK
./gradlew :app:assembleRelease                        # release APK (requires keystore)

# Wear OS app
./gradlew :wear:assembleDebug

# Tests
./gradlew :app:test                                   # all unit tests
./gradlew :app:test --tests "com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModelTest"  # single class
./gradlew :app:test --tests "*.PlayerViewModelTest.someMethodName"  # single test method

# Lint (disabled on release builds by default; run explicitly)
./gradlew :app:lint
```

Tests use **JUnit 5 (Jupiter)** via `useJUnitPlatform()`. The test toolkit is MockK + Turbine + Google Truth + `kotlinx-coroutines-test`. Use `MainCoroutineExtension` (in `app/src/test`) to inject a `TestDispatcher` for coroutine tests.

ABI splits produce `app-arm64-v8a-debug.apk` and `app-armeabi-v7a-debug.apk`. Pass `-Ppixelplay.enableAbiSplits=false` when you just need a single installable APK.

To generate Compose compiler reports/metrics set `-Ppixelplay.enableComposeCompilerReports=true`.

## Module Structure

| Module | Purpose |
|--------|---------|
| `:app` | Phone application — all UI, business logic, media playback |
| `:wear` | Wear OS companion app — remote control + local playback on watch |
| `:shared` | KMP-ready data transfer contracts (`WearDataPaths`, `WearPlayerState`, `WearPlaybackCommand`, etc.) shared between `:app` and `:wear` |
| `:baselineprofile` | Generates baseline profiles for startup dex-layout optimisation |

## Architecture Overview

### Phone app (`app/`)

The app follows **MVVM** with Hilt DI. The package root is `com.theveloper.pixelplay`.

**Data layer** (`data/`)
- `data/model/` — domain models (`Song`, `Album`, `Artist`, `Genre`, `Playlist`, …)
- `data/database/` — Room `PixelPlayDatabase` (v42, ~25 entities). Schema exports live in `app/schemas/`. Add a `Migration` object to `PixelPlayDatabase` for every schema bump.
- `data/repository/` — `MusicRepository` (interface) / `MusicRepositoryImpl` (Room + `MediaStoreSongRepository`). The repository is the single source of truth for library data; it exposes `Flow<List<Song>>` and paginated `Flow<PagingData<…>>` variants.
- `data/preferences/` — DataStore-backed preference repositories (`UserPreferencesRepository`, `ThemePreferencesRepository`, `AiPreferencesRepository`, `EqualizerPreferencesRepository`, …).
- `data/network/` — Retrofit services for LRCLIB (lyrics), Deezer (artist art), Netease, Navidrome, Jellyfin, QQ Music.
- `data/service/MusicService.kt` — `MediaLibraryService` that owns the `MediaSession`. Everything related to background playback, notifications, Android Auto browse tree, and sleep timer lives here.
- `data/service/player/DualPlayerEngine.kt` — wraps two `ExoPlayer` instances (active + pre-roll) and a `CastPlayer` to support gapless playback and Chromecast. The service delegates to `DualPlayerEngine`; the UI communicates only through `MediaController`.
- `data/service/http/MediaFileHttpServerService.kt` — Ktor/CIO HTTP server that streams local audio files to Chromecast (since Cast cannot read `content://` URIs directly).
- `data/service/wear/` — Wearable Data Layer bridge: `WearStatePublisher` pushes player state to the watch; `WearCommandReceiver` processes commands from the watch.
- `data/worker/SyncWorker.kt` — WorkManager worker that scans MediaStore and writes results to Room. `SyncManager` schedules it.
- `data/ai/` — AI playlist generation (`AiPlaylistGenerator`, `AiHandler`) with a pluggable provider abstraction (`AiClient`, `GeminiAiClient`, `GenericOpenAiClient`).
- `data/stream/` — Streaming sources (Navidrome, Jellyfin, Google Drive, Telegram, Netease, QQ Music) each with their own DAO, entity, network service, and repository.

**Presentation layer** (`presentation/`)
- `presentation/viewmodel/PlayerViewModel.kt` — the central ViewModel. It holds `PlayerUiState` (a large state class), connects to `MediaController`, and delegates to a set of **state holder singletons** (see below). Most screens read from `PlayerViewModel`.
- **State holders** — singleton `@Inject`ed helpers that decompose `PlayerViewModel`'s responsibilities: `PlaybackStateHolder`, `QueueStateHolder`, `LyricsStateHolder`, `LibraryStateHolder`, `ThemeStateHolder`, `CastStateHolder`, `AiStateHolder`, …. Each owns a focused `StateFlow` and is combined by `PlayerViewModel`.
- `presentation/components/` — reusable Compose components. The full-screen player sheet lives in `UnifiedPlayerSheetV2.kt` with geometry/motion split across the `scoped/` sub-package (`SheetMotionController`, `SheetVerticalDragGestureHandler`, `SheetOverlayState`, …).
- `presentation/navigation/AppNavigation.kt` — single `NavHost` with all phone routes (`Screen.kt` defines route strings).
- `ui/theme/` — Material 3 theming: `Theme.kt`, `ColorRoles.kt`, `GenreColors.kt`. Dynamic color from album art is extracted via Palette API and stored in `AlbumArtThemeEntity`.
- `ui/glancewidget/` — Glance home-screen widgets (3×2 control, 4×1 bar, 2×2 grid).

### Wear OS app (`wear/`)

Mirrors the phone structure. `WearDataListenerService` receives messages on Wearable Data Layer paths (constants in `shared/WearDataPaths`). `WearPlayerViewModel` / `WearBrowseViewModel` drive the watch UI. The watch can either remote-control the phone or play locally via `WearPlaybackService` + `WearLocalPlayerRepository` (Room-backed local song cache).

### Phone ↔ Watch communication

All paths are constants in `shared/WearDataPaths`. The phone pushes a JSON `WearPlayerState` DataItem to `/player_state`. The watch sends `WearPlaybackCommand` messages to `/playback_command`. File transfer for local watch playback uses `ChannelClient` on `/transfer_audio`.

## Key Patterns

**Compose state slicing** — `PlayerUiState` is large (~30 fields, including `currentPosition` updated at 250 ms). Screens that only need a subset must slice it to avoid over-recomposition:

```kotlin
// Correct pattern (used in UnifiedPlayerSheetV2)
val slice by playerViewModel.playerUiState
    .map { PlayerUiSheetSliceV2(it.isPlaying, it.currentSong, …) }
    .distinctUntilChanged()
    .collectAsStateWithLifecycle(…)

// Anti-pattern — triggers recomposition on every position tick
val state by playerViewModel.playerUiState.collectAsStateWithLifecycle()
```

**ImmutableList** — queue and search results use `kotlinx.collections.immutable.ImmutableList` / `PersistentList` throughout to enable Compose strong-skipping.

**Compose stability config** — `app/compose_stability.conf` marks additional classes stable so the compiler can skip them.

**Room migrations** — schema version is currently **42**. Every entity change requires a numbered `Migration` object registered in `PixelPlayDatabase.kt` and a corresponding schema JSON exported to `app/schemas/`.

**MediaController binding** — the UI never touches `ExoPlayer` directly. `PlayerViewModel` binds a `MediaController` to `MusicService`'s `MediaSession`. Custom commands (e.g. toggle shuffle, set transition) are sent as `SessionCommand` strings.

## External APIs

| API | Used for |
|-----|----------|
| LRCLIB | Synchronized lyrics (LRC format) |
| Deezer | Artist artwork |
| Netease / QQ Music | Chinese streaming library |
| Navidrome / Jellyfin | Self-hosted music server streaming |
| Google Drive | Cloud library source |
| Telegram (TDLib) | Telegram music channels |
| Gemini / OpenAI-compatible | AI playlist generation |

API keys and credentials are never committed. Telegram `TELEGRAM_API_ID`/`TELEGRAM_API_HASH` are read from `local.properties`. Gemini/OpenAI keys are stored via `androidx.security.crypto` at runtime.
