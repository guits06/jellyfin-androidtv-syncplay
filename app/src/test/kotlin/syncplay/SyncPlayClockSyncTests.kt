package org.jellyfin.androidtv.syncplay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.SyncPlayApi
import org.jellyfin.sdk.api.operations.TimeSyncApi
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.UtcTimeResponse
import java.time.LocalDateTime
import java.time.ZoneOffset

class SyncPlayClockSyncTests : FunSpec({
	test("computeOffset accurately calculates clock difference") {
		// Client sends at t0=1000
		// Server receives at t1=1050 (server is +30ms ahead of client: true client time 1020)
		// Server replies at t2=1060 (server time)
		// Client receives at t3=1100
		// One-way transit time = ((1100-1000) - (1060-1050)) / 2 = 90 / 2 = 45ms
		// Server clock offset = ((1050 - 1000) + (1060 - 1100)) / 2 = (50 - 40) / 2 = 5ms
		val t0 = 1000L
		val t1 = 1050L
		val t2 = 1060L
		val t3 = 1100L

		val offset = SyncPlayClockSync.computeOffset(t0, t1, t2, t3)
		offset shouldBe 5L

		val rtt = SyncPlayClockSync.computeRtt(t0, t1, t2, t3)
		rtt shouldBe 90L
	}

	test("computeRtt accounts for server processing delay") {
		val t0 = 2000L
		val t1 = 2020L
		val t2 = 2050L // 30ms spent inside server
		val t3 = 2080L

		// Total round-trip: (2080 - 2000) - (2050 - 2020) = 80 - 30 = 50ms
		SyncPlayClockSync.computeRtt(t0, t1, t2, t3) shouldBe 50L
	}

	test("synchronizeTime selects sample with lowest RTT and reports ping") {
		val mockApi = mockk<ApiClient>()
		val mockTimeSyncApi = mockk<TimeSyncApi>()
		val mockSyncPlayApi = mockk<SyncPlayApi>(relaxed = true)

		val clockSync = SyncPlayClockSync(
			api = mockApi,
			timeSyncApi = mockTimeSyncApi,
			syncPlayApi = mockSyncPlayApi,
		)

		val serverTime = clockSync.epochMilliToLocalDateTime(1700000000000L)
		val mockResponse = mockk<Response<UtcTimeResponse>>()
		coEvery { mockResponse.content } returns UtcTimeResponse(
			requestReceptionTime = serverTime,
			responseTransmissionTime = serverTime,
		)
		coEvery { mockTimeSyncApi.getUtcTime() } returns mockResponse

		val bestSample = clockSync.synchronizeTime(sampleCount = 3)

		bestSample shouldNotBe null
		coVerify(atLeast = 1) { mockSyncPlayApi.syncPlayPing(any<PingRequestDto>()) }
	}

	test("serverToLocalEpochMs and localToServerEpochMs convert correctly") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val clockSync = SyncPlayClockSync(mockApi)

		// With default 0 offset:
		clockSync.serverToLocalEpochMs(5000L) shouldBe 5000L
		clockSync.localToServerEpochMs(5000L) shouldBe 5000L
	}
})
