# Resumen de Cambios — SyncPlay + Rediseño Material 3 Expressive TV

Rama: `feat/syncplay-fix-and-ui-redesign`

---

## 1. Refactorización y Estabilización de SyncPlay

- **Máquina de estados explícita (`SyncPlayState.kt`, `SyncPlayManager.kt`, `SyncPlayPlaybackIntegration.kt`)**:
  - Se reemplazó el flag booleano síncrono `isHandlingRemoteCommand` por orígenes de evento explícitos (`SyncPlayEventOrigin`: `USER_LOCAL`, `REMOTE_SYNCPLAY`, `INTERNAL_PLAYER_TRANSITION`) y motivos de transición (`PlaybackTransitionReason`).
  - Ventanas de supresión y expectativa remota sobre acciones asíncronas de ExoPlayer (`play()`, `pause()`, `seek()`) para evitar comandos fantasma o bucles de re-seek.
- **Transparencia en cambio de pistas y quemado de subtítulos (`PlaybackController.java`, `PlaybackControllerHelper.kt`, `VideoManager.java`, `StreamInfo.java`)**:
  - Los reinicios internos por cambio de audio o activación de subtítulos con quemado/transcodificación (`INTERNAL_PLAYER_TRANSITION`) ya no disparan `Stop` ni `SetNewQueue`, conservan el estado pausado/reproduciendo esperado del grupo y re-sincronizan posición al terminar `onPrepared()`.
  - `VideoManager.onIsPlayingChanged` distingue preparación inicial de reanudaciones/buffering reales.
  - Guardas nulas en `StreamInfo` y `PlaybackController` para evitar crashes al recomponer streams con quemado.
- **Indicador SyncPlay en el reproductor (`CustomPlaybackOverlayFragment.java`)**:
  - Observa tanto `currentGroup` como `groupState` para mostrar estado en vivo (`PLAYING`, `WAITING`, `PAUSED`, `IDLE`) con código de color en el HUD OSD durante la reproducción.
- **Herramienta y Tests (`SyncPlayStateMachineTests.kt`, `tools/syncplay_test_client.py`)**:
  - Tests unitarios para máquina de estados, planificación temporal (`SyncPlayClockSync`), prevención de bucles y transiciones de grupo (`24` tests en `org.jellyfin.androidtv.syncplay.*`).
  - Cliente de prueba Python para validar escenarios multi-cliente contra el servidor Jellyfin.

---

## 2. Rediseño UI Material 3 Expressive + Estilo Netflix para Android TV

- **Sistema de diseño y motor nativo (`ui/design/`, `cpp/cinefast.c`, `CineFastNative.kt`)**:
  - `CinemaTheme.kt` (`CinemaPalette`, `CinemaMotion`, `CinemaSpacing`), `CinemaComponents.kt` (`CinemaAmbientCanvas`, `CinemaHeroScrim`, `CinemaMediaCard`, `CinemaActionButton`, `CinemaFilterChip`, `CinemaSectionRow`) y `CinemaNavigationRail.kt` (`CinemaNavDestination`, rail lateral superpuesto con cajón SyncPlay integrado).
  - Librería nativa C (`libcinefast.so`) para muestreo de color ambiente (`64x64`) y generación de texturas ambient/scrim en CPU sin saturar fragment shaders en GPUs modestas.
- **Pantallas migradas a Jetpack Compose puro (`androidx.tv:tv-material:1.1.0`)**:
  - **Home (`HomeFragment.kt`, `HomeScreen.kt`, `HomeViewModel.kt`)**: Hero billboard inmersivo con debounce de foco (`350 ms`) y filas fluidas.
  - **Bibliotecas / Películas / Anime / CD (`LibraryGridScreen.kt`, `LibraryGridViewModel.kt`, `LibrarySmartScreen.kt`, `LibrarySmartViewModel.kt`, `BrowseGridFragment.java`, `BrowseViewFragment.java`, `CollectionFragment.kt`, `GenericFolderFragment.kt`)**: Vista unificada con chips D-pad y rejilla/filas optimizadas.
  - **Detalle de Álbum y Reproducción de Audio (`AlbumDetailScreen.kt`, `AlbumDetailViewModel.kt`, `ItemListFragment.java`, `MusicFavoritesListFragment.java`, `AudioNowPlayingScreen.kt`, `AudioNowPlayingFragment.java`)**.
  - **Ficha de Media (`MediaDetailScreen.kt`, `MediaDetailViewModel.kt`, `FullDetailsFragment.java`)**: Portada/logo inmersivo, selector en línea de Audio y Subtítulos previo a reproducir, temporadas/episodios, reparto, similares y lanzamiento directo con SyncPlay (manteniendo fallback Leanback solo para temporizadores LiveTV).
  - **Búsqueda (`SearchScreen.kt`, `SearchFragment.kt`)**: Barra de búsqueda D-pad/voz y chips de filtro por categoría.
- **Supresión de overdraw legado (`MainActivity.kt`, `AppBackground.kt`)**: Se desactiva el fondo redundante en destinos Compose.

---

## 3. Capturas Reales en Chromecast HD (Release + AOT)

| Pantalla | Captura |
|---|---|
| Inicio (Hero + Filas) | `docs/screenshots/home.png` |
| Biblioteca Anime / Smart Hub | `docs/screenshots/library.png` |
| Hub Música | `docs/screenshots/music_hub.png` |
| Detalle de Álbum / CD | `docs/screenshots/album.png` |
| Ficha de Serie / Episodio | `docs/screenshots/detail_series.png` |
| Búsqueda | `docs/screenshots/search.png` |
| Cajón SyncPlay en Navegación | `docs/screenshots/syncplay_drawer.png` |

---

## 4. Verificación Final y Entregables

- **Tests unitarios (`./gradlew :app:testDebugUnitTest --tests "org.jellyfin.androidtv.*"`)**: `120` tests ejecutados — **`120` pasados, `0` fallos, `0` errores, `0` omitidos** (incluyendo `SyncPlayStateMachineTests`, `SyncPlayClockSyncTests`, `SyncPlayCommandSchedulingTests`, `SyncPlayLoopPreventionTests`, `SyncPlayStateTransitionTests` y `CinemaRedesignTests`).
- **Análisis estático (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew detekt --no-daemon`)**: **`BUILD SUCCESSFUL`** (código de salida `0`).
- **APK Release R8 firmado**: `outputs/jellyfin-androidtv-syncplay-release.apk` (`24,465,570` bytes), instalado en Chromecast HD (`org.jellyfin.androidtv`) con compilación AOT (`status=speed`).
- **Comparativa de rendimiento completa**: ver `PERF_REPORT.md`, línea base en `PERF_BASELINE.md` y registro de decisiones en `DECISIONS.md`.
