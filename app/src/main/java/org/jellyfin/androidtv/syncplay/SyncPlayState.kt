package org.jellyfin.androidtv.syncplay

/**
 * Origin of a playback or SyncPlay state mutation.
 * Used to distinguish genuine local user intent from remote SyncPlay commands
 * and internal player rebuilds (e.g., subtitle burn-in or audio transcode switching).
 */
enum class SyncPlayEventOrigin {
	USER_LOCAL,
	REMOTE_SYNCPLAY,
	INTERNAL_PLAYER_TRANSITION,
}

/**
 * Reason for an internal player transition during which local stop/start/buffering
 * signals must not be forwarded as user actions to SyncPlay.
 */
enum class PlaybackTransitionReason {
	NONE,
	INITIAL_PREPARE,
	TRACK_SWITCH,
	BURN_IN_REBUILD,
	REMOTE_COMMAND,
	QUEUE_SWITCH,
}
