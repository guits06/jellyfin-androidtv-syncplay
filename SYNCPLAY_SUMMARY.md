# Resumen de Implementación de SyncPlay en Jellyfin Android TV

Este documento resume todo el trabajo realizado para implementar **SyncPlay** en el cliente `jellyfin-androidtv`, así como las correcciones de sincronización, ciclo de vida y estabilidad descubiertas durante las pruebas en dispositivo real (Chromecast con Google TV vía ADB).

---

## 1. Arquitectura Principal (`syncplay/`)

Se creó el paquete `app/src/main/java/org/jellyfin/androidtv/syncplay/` con tres componentes desacoplados:

- **`SyncPlayClockSync.kt`**:
  - Sincroniza el reloj local con el servidor mediante `TimeSyncApi.getUtcTime()` (`GET /GetUtcTime`).
  - Toma múltiples muestras (`DEFAULT_SAMPLE_COUNT`), calcula RTT y offset de reloj, selecciona la muestra de menor RTT y reporta el ping al servidor (`POST /SyncPlay/Ping`).
  - Expone `clockOffsetMs` y `currentPingMs` como `StateFlow<Long>`, además de utilidades de conversión temporal (`serverToLocalEpochMs`, `calculateDelayMs`) para programar comandos remotos según `When`.

- **`SyncPlayManager.kt`**:
  - Gestiona el estado del grupo SyncPlay mediante `StateFlow`: `currentGroup`, `groupState` (`GroupStateType`), `playQueue` (`PlayQueueUpdate`) e `isInGroup`.
  - Expone un `SharedFlow<SendCommand>` (`commands`) para los comandos recibidos por WebSocket.
  - Se suscribe a `SyncPlayCommandMessage` y `SyncPlayGroupUpdateMessage` en `api.webSocket`, manejando actualizaciones de grupo (`SyncPlayGroupJoinedUpdate`, `SyncPlayGroupLeftUpdate`, `SyncPlayStateUpdate`, `SyncPlayPlayQueueUpdate`, usuarios que entran/salen, errores de acceso o grupo inexistente).
  - Gestiona el ciclo de vida: recuperación de grupo activo al iniciar sesión o reconectar WebSocket (`checkActiveGroup()`), limpieza de estado al cerrar sesión (`sessionRepository.currentSession`) y recuperación de `currentGroup` si llega una actualización o comando sin estado previo.
  - Implementa las llamadas a `SyncPlayApi`: `getGroups`, `createGroup`, `joinGroup`, `leaveGroup`, `getGroup`, `setNewQueue`, `nextItem`, `previousItem`, `sendPause`, `sendUnpause`, `sendSeek`, `sendStop`, `sendBuffering` y `sendReady`.
  - Mantiene el flag `isHandlingRemoteCommand` para evitar bucles de eventos entre acciones remotas y locales.

- **`SyncPlayPlaybackIntegration.kt`**:
  - Puente bidireccional entre `SyncPlayManager` y `PlaybackController` / `PlaybackHelper`.
  - **Recepción de comandos remotos (`executeRemoteCommand`)**: aplica `PAUSE`, `UNPAUSE`, `SEEK` y `STOP` respetando el retardo programado por `When` compensado con `SyncPlayClockSync`.
  - **Observación de estado de grupo (`observeGroupState`)**: escucha `syncPlayManager.groupState` para reanudar reproducción local cuando el grupo pasa a `PLAYING` o pausar cuando pasa a `PAUSED`/`WAITING`, siempre protegiendo con `isHandlingRemoteCommand = true`.
  - **Observación de cola (`observePlayQueue`)**: carga y reproduce automáticamente el ítem activo cuando cambia `PlayQueueUpdate`.
  - **Monitor de deriva (`startDriftMonitor` / `checkAndCorrectDrift`)**: ajusta suavemente la velocidad de reproducción (`0.96f` / `1.04f`) ante derivas menores y realiza seek de corrección si la desviación supera el umbral configurado.
  - **Eventos locales hacia el servidor**: `onUserStartPlayback` (con prevención de `setNewQueue` duplicado si el ítem ya está activo en la cola), `onUserPause`, `onUserPlay`, `onUserSeek`, `onUserStop`, `onUserNext`, `onUserPrevious`, `onPlayerBuffering` y `onPlayerPrepared`.

---

## 2. Integración en Reproductor e Inyección de Dependencias

- **Koin DI (`KoinInitializer.kt`, `PlaybackModule.kt`)**:
  - Se añadieron `SyncPlayClockSync`, `SyncPlayManager` y `SyncPlayPlaybackIntegration` en `PlaybackModule.kt` con `createdAtStart = true` (y soporte en `KoinInitializer.kt` con `createEagerInstances()`) para que las suscripciones WebSocket estén activas desde el arranque de la app, incluso antes de abrir un vídeo.
- **`PlaybackController.java` y `PlaybackControllerContainer.kt`**:
  - Se integraron los hooks de SyncPlay para inicio de reproducción, pausa, reanudación, seek, buffering, preparación y parada real en `endPlayback()` (evitando enviar `Stop` al servidor en paradas internas por cambio de pista o subtítulos).
  - Se expuso `playbackControllerFlow` en `PlaybackControllerContainer.kt` para observar sesiones activas de reproducción.
  - Se añadieron métodos de apoyo como `setPlaybackSpeed` en `PlaybackControllerNotifiable.kt` y `VideoManager.java`.
- **`CoroutineUtils.kt`**:
  - Se añadió soporte auxiliar de corrutinas utilizado en la integración Java/Kotlin.

---

## 3. Interfaz de Usuario para Android TV (D-pad)

- **Ajustes (`SettingsSyncPlayScreen.kt`, `SettingsPlaybackScreen.kt`, `routes.kt`)**:
  - Nueva pantalla `SettingsPlaybackSyncPlayScreen` accesible desde los ajustes de reproducción para listar grupos disponibles, crear un grupo nuevo, unirse, ver participantes y salir del grupo actual.
- **Reproductor (`SyncPlayAction.kt`, `CustomPlaybackTransportControlGlue.java`, `CustomPlaybackOverlayFragment.java`, `vlc_player_interface.xml`)**:
  - Nueva acción `SyncPlayAction` en los controles del reproductor con menú contextual adaptado a TV para crear/unirse/salir de grupos durante la reproducción.
  - Indicador visual (badge/icono de SyncPlay activo) en el overlay del reproductor (`badge_syncplay.xml`, `ic_syncplay.xml`).
- **Textos y traducciones (`values/strings.xml`, `values-es/strings.xml`)**:
  - Se añadieron 16 cadenas de texto en inglés base y español para títulos, estados, participantes, creación/unión/salida de grupos y avisos toast.

---

## 4. Incidencias Resueltas en Pruebas Reales (ADB / Chromecast con Google TV)

Durante la depuración en dispositivo real frente al servidor Jellyfin se detectaron y corrigieron los siguientes problemas:

1. **TV pausada mientras el PC reproducía**:
   - **Causa**: `onPlayerPrepared()` evaluaba `isPlaying` antes de pausar localmente cuando el grupo estaba en `WAITING`/`PAUSED`, reportando `isPlaying = true` en `POST /SyncPlay/Ready`. El servidor (`WaitingGroupState.cs`) enviaba entonces `Unpause` con filtro `AllExceptCurrentSession`, excluyendo a la TV. Además, `onPlayerPrepared()` se llamaba al inicio de `onPrepared()` y una rama heredada devolvía `mPlaybackState` a `PLAYING` dejando `mVideoManager` pausado.
   - **Solución**:
     - Se movió `onPlayerPrepared()` al final de `PlaybackController.onPrepared()`.
     - En `onPlayerPrepared()`, cuando el grupo está en `WAITING` o `PAUSED`, primero se pausa localmente y se envía `sendReady(..., isPlaying = false, ...)`.
     - Se añadió `observeGroupState()` en `SyncPlayPlaybackIntegration` para reanudar automáticamente si el estado del grupo pasa a `PLAYING`.
     - En `PlaybackController.play()`, si `mPlaybackState == PLAYING` pero `!mVideoManager.isPlaying()`, se reanuda `mVideoManager.play()`.

2. **Parada falsa de SyncPlay al cambiar audio o subtítulos**:
   - **Causa**: `PlaybackController.stop()` se usa internamente al reconstruir streams (por ejemplo, al cambiar pistas o quemar subtítulos) y estaba disparando `POST /SyncPlay/Stop`.
   - **Solución**: Se movió `onUserStop()` exclusivamente a `endPlayback(...)`.

3. **Crash (`NullPointerException`) al activar subtítulos en español que requieren quemado**:
   - **Causa**: `StreamInfo.getSubtitleDeliveryMethod()` consultaba el índice de subtítulo por defecto del `MediaSource` (`-1`) en lugar del seleccionado en `mCurrentOptions`, marcando `burningSubs = false`. Al terminar de preparar, `onPrepared()` llamaba a `setSubtitleIndex(..., force = true)`, reiniciaba el reproductor (`stop()` dejaba `mCurrentStreamInfo = null`) y `onPrepared()` continuaba ejecutándose hasta fallar en `getCurrentMediaSource().getDefaultAudioStreamIndex()`.
   - **Solución**:
     - `PlaybackController.updateBurningSubs()` ahora comprueba el `MediaStream` del índice elegido en `mCurrentOptions.getSubtitleStreamIndex()`.
     - `PlaybackControllerHelper.kt` evita reiniciar si ya se están quemando esos mismos subtítulos y añade comprobaciones nulas en `currentMediaSource` y `currentStreamInfo`.
     - `PlaybackController.onPrepared()` retorna inmediatamente si cambiar subtítulos detuvo/reinició la reproducción (`IDLE` / `BUFFERING`), y protege con comprobación nula `getCurrentMediaSource()` tanto en `onPrepared()` como en `getAudioStreamIndex()`.
     - `StreamInfo.getSubtitleDeliveryMethod()` valida nulidad y límites del listado de streams.

---

## 5. Tests y Verificación

Se crearon 4 suites de tests unitarios en `app/src/test/kotlin/syncplay/`:
- `SyncPlayClockSyncTests.kt`: cálculo de RTT, offset de reloj y selección de mejor muestra.
- `SyncPlayCommandSchedulingTests.kt`: programación temporal de comandos según `When` y compensación de reloj.
- `SyncPlayLoopPreventionTests.kt`: prevención de bucles entre comandos remotos y acciones locales.
- `SyncPlayStateTransitionTests.kt`: transiciones de estado de grupo, recuperación y actualizaciones WebSocket.

Comandos verificados tras los cambios de código:
- `./gradlew :app:testDebugUnitTest` y `./gradlew test`: todos los tests pasaron correctamente.
- `./gradlew assembleDebug`: compilación sin errores.
- Despliegue en dispositivo Android TV conectado por ADB (`adb install -r ...`).

---

## 6. Inventario de Archivos

### Archivos creados
- `app/src/main/java/org/jellyfin/androidtv/syncplay/SyncPlayClockSync.kt`
- `app/src/main/java/org/jellyfin/androidtv/syncplay/SyncPlayManager.kt`
- `app/src/main/java/org/jellyfin/androidtv/syncplay/SyncPlayPlaybackIntegration.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/overlay/action/SyncPlayAction.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/settings/screen/playback/SettingsSyncPlayScreen.kt`
- `app/src/main/res/drawable/badge_syncplay.xml`
- `app/src/main/res/drawable/ic_syncplay.xml`
- `app/src/test/kotlin/syncplay/SyncPlayClockSyncTests.kt`
- `app/src/test/kotlin/syncplay/SyncPlayCommandSchedulingTests.kt`
- `app/src/test/kotlin/syncplay/SyncPlayLoopPreventionTests.kt`
- `app/src/test/kotlin/syncplay/SyncPlayStateTransitionTests.kt`

### Archivos modificados
- `app/src/main/java/org/jellyfin/androidtv/data/compat/StreamInfo.java`
- `app/src/main/java/org/jellyfin/androidtv/di/KoinInitializer.kt`
- `app/src/main/java/org/jellyfin/androidtv/di/PlaybackModule.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/CustomPlaybackOverlayFragment.java`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackController.java`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackControllerContainer.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackControllerHelper.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackControllerNotifiable.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/overlay/CustomPlaybackTransportControlGlue.java`
- `app/src/main/java/org/jellyfin/androidtv/ui/settings/routes.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/settings/screen/playback/SettingsPlaybackScreen.kt`
- `app/src/main/java/org/jellyfin/androidtv/util/CoroutineUtils.kt`
- `app/src/main/res/layout/vlc_player_interface.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-es/strings.xml`
