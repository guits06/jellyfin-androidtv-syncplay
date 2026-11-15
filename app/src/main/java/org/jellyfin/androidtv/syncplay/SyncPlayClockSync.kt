package org.jellyfin.androidtv.syncplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.client.extensions.timeSyncApi
import org.jellyfin.sdk.api.operations.SyncPlayApi
import org.jellyfin.sdk.api.operations.TimeSyncApi
import org.jellyfin.sdk.model.api.PingRequestDto
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SyncPlayClockSync(
	private val api: ApiClient,
	private val timeSyncApi: TimeSyncApi? = null,
	private val syncPlayApi: SyncPlayApi? = null,
) {
	private val effectiveTimeSyncApi: TimeSyncApi get() = timeSyncApi ?: api.timeSyncApi
	private val effectiveSyncPlayApi: SyncPlayApi get() = syncPlayApi ?: api.syncPlayApi

	data class SyncSample(
		val rttMs: Long,
		val offsetMs: Long,
	)

	private val _clockOffsetMs = MutableStateFlow(0L)
	val clockOffsetMs: StateFlow<Long> = _clockOffsetMs.asStateFlow()

	private val _currentPingMs = MutableStateFlow(0L)
	val currentPingMs: StateFlow<Long> = _currentPingMs.asStateFlow()

	fun localDateTimeToEpochMilli(dateTime: LocalDateTime): Long =
		dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

	fun epochMilliToLocalDateTime(epochMs: Long): LocalDateTime =
		Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()

	@Suppress("TooGenericExceptionCaught")
	suspend fun synchronizeTime(sampleCount: Int = DEFAULT_SAMPLE_COUNT): SyncSample? = withContext(Dispatchers.IO) {
		val samples = mutableListOf<SyncSample>()

		for (i in 0 until sampleCount) {
			try {
				val t0 = System.currentTimeMillis()
				val response = effectiveTimeSyncApi.getUtcTime()
				val t3 = System.currentTimeMillis()

				val t1 = localDateTimeToEpochMilli(response.content.requestReceptionTime)
				val t2 = localDateTimeToEpochMilli(response.content.responseTransmissionTime)

				val rtt = computeRtt(t0, t1, t2, t3)
				val offset = computeOffset(t0, t1, t2, t3)

				samples.add(SyncSample(rttMs = rtt, offsetMs = offset))
			} catch (e: Exception) {
				Timber.w(e, "SyncPlayClockSync: Failed to get time sample #$i")
			}
		}

		val bestSample = samples.minByOrNull { it.rttMs }
		if (bestSample != null) {
			_clockOffsetMs.value = bestSample.offsetMs
			_currentPingMs.value = bestSample.rttMs
			Timber.i("SyncPlayClockSync: Synchronized with offset=${bestSample.offsetMs}ms, RTT=${bestSample.rttMs}ms")

			try {
				effectiveSyncPlayApi.syncPlayPing(PingRequestDto(ping = bestSample.rttMs))
			} catch (e: Exception) {
				Timber.w(e, "SyncPlayClockSync: Failed to report ping to server")
			}
		}
		bestSample
	}

	fun serverToLocalEpochMs(serverEpochMs: Long): Long = serverEpochMs - _clockOffsetMs.value

	fun localToServerEpochMs(localEpochMs: Long): Long = localEpochMs + _clockOffsetMs.value

	fun calculateDelayMs(whenUtc: LocalDateTime): Long {
		val targetServerEpochMs = localDateTimeToEpochMilli(whenUtc)
		val targetLocalEpochMs = serverToLocalEpochMs(targetServerEpochMs)
		val currentLocalMs = System.currentTimeMillis()
		return targetLocalEpochMs - currentLocalMs
	}

	companion object {
		const val DEFAULT_SAMPLE_COUNT = 5

		fun computeOffset(t0: Long, t1: Long, t2: Long, t3: Long): Long =
			((t1 - t0) + (t2 - t3)) / 2

		fun computeRtt(t0: Long, t1: Long, t2: Long, t3: Long): Long =
			(t3 - t0) - maxOf(0L, t2 - t1)
	}
}

