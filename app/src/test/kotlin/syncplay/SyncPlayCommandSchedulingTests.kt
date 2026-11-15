package org.jellyfin.androidtv.syncplay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class SyncPlayCommandSchedulingTests : FunSpec({
	test("calculateDelayMs returns positive value for future When timestamp") {
		val mockApi = mockk<org.jellyfin.sdk.api.client.ApiClient>(relaxed = true)
		val clockSync = SyncPlayClockSync(mockApi)

		val futureEpochMs = System.currentTimeMillis() + 5000L
		val futureUtc = clockSync.epochMilliToLocalDateTime(futureEpochMs)

		val delay = clockSync.calculateDelayMs(futureUtc)
		delay shouldBeGreaterThan 4000L
		delay shouldBeLessThan 6000L
	}

	test("calculateDelayMs returns non-positive value for past When timestamp") {
		val mockApi = mockk<org.jellyfin.sdk.api.client.ApiClient>(relaxed = true)
		val clockSync = SyncPlayClockSync(mockApi)

		val pastEpochMs = System.currentTimeMillis() - 2000L
		val pastUtc = clockSync.epochMilliToLocalDateTime(pastEpochMs)

		val delay = clockSync.calculateDelayMs(pastUtc)
		(delay <= 0L) shouldBe true
	}

	test("calculateDelayMs accurately compensates for clock offset") {
		val mockApi = mockk<org.jellyfin.sdk.api.client.ApiClient>(relaxed = true)
		val clockSync = SyncPlayClockSync(mockApi)

		// Set internal offset via reflection or sampling
		val field = SyncPlayClockSync::class.java.getDeclaredField("_clockOffsetMs")
		field.isAccessible = true
		@Suppress("UNCHECKED_CAST")
		val offsetFlow = field.get(clockSync) as kotlinx.coroutines.flow.MutableStateFlow<Long>
		offsetFlow.value = 1000L // Server is 1000ms ahead

		val now = System.currentTimeMillis()
		// Server target time is now + 1000 (which is current server time) + 2000 (delay) = now + 3000
		val serverTargetUtc = clockSync.epochMilliToLocalDateTime(now + 3000L)

		val delay = clockSync.calculateDelayMs(serverTargetUtc)
		// Local delay should be: (now + 3000 - offset(1000)) - now = 2000ms
		delay shouldBeGreaterThan 1800L
		delay shouldBeLessThan 2200L
	}
})
