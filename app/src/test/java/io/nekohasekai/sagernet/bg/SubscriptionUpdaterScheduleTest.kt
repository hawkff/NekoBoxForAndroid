package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.SubscriptionBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdaterScheduleTest {

    @Test
    fun expiryReminder_isDailyInsideTheLastThreeDaysAndOneDayPast() {
        val day = 24 * 3600L
        val expiry = 100 * day
        assertFalse(SubscriptionUpdater.expiryReminderDue(expiry, notifiedAt = 0, nowSeconds = expiry - 4 * day))
        assertTrue(SubscriptionUpdater.expiryReminderDue(expiry, notifiedAt = 0, nowSeconds = expiry - 3 * day))
        assertFalse(SubscriptionUpdater.expiryReminderDue(expiry, notifiedAt = (expiry - 3 * day).toInt(), nowSeconds = expiry - 2 * day - 1))
        assertTrue(SubscriptionUpdater.expiryReminderDue(expiry, notifiedAt = (expiry - 3 * day).toInt(), nowSeconds = expiry - 2 * day))
        assertTrue(SubscriptionUpdater.expiryReminderDue(expiry, notifiedAt = 0, nowSeconds = expiry + day - 1))
        assertFalse(SubscriptionUpdater.expiryReminderDue(expiry, notifiedAt = 0, nowSeconds = expiry + day))
    }

    @Test
    fun expiry_comesFromUserinfo() {
        assertEquals(1790951622L, SubscriptionBean().apply { subscriptionUserinfo = "upload=0; download=2; total=0; expire=1790951622" }.expiry())
        assertNull(SubscriptionBean().apply { subscriptionUserinfo = "upload=0; download=2" }.expiry())
        assertNull(SubscriptionBean().expiry())
    }

    @Test
    fun overdueSubscription_schedulesImmediately() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 15)),
            nowSeconds = 1_900,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(0L, schedule.initialDelaySeconds)
    }

    @Test
    fun nearFutureSubscription_usesSecondsUntilDue() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 15)),
            nowSeconds = 1_870,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(30L, schedule.initialDelaySeconds)
    }

    @Test
    fun farFutureSubscription_preservesSecondsUntilDue() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 60)),
            nowSeconds = 1_100,
        )!!

        assertEquals(60L, schedule.intervalMinutes)
        assertEquals(3_500L, schedule.initialDelaySeconds)
    }

    @Test
    fun delayBelowWorkManagerMinimum_isCoercedForIntervalAndDueTime() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 5)),
            nowSeconds = 1_870,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(30L, schedule.initialDelaySeconds)
    }

    @Test
    fun multipleSubscriptions_useSoonestDueSubscription() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(
                SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 60),
                SubscriptionScheduleInput(lastUpdated = 2_000, autoUpdateDelay = 15),
            ),
            nowSeconds = 2_870,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(30L, schedule.initialDelaySeconds)
    }
}
