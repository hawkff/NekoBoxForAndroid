package io.nekohasekai.sagernet.utils

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import androidx.room.Room
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogEvent
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import io.sentry.transport.NoOpTransport
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class TelemetryTest {
    private val analyticsCache get() = RuntimeEnvironment.getApplication().cacheDir.resolve("telemetry-posthog")

    @Test
    fun switchDefaultsOnAndPersistsOptOutAcrossInflation() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomPreferenceDataStore(db.keyValuePairDao())
            fun preference(): SwitchPreference {
                val manager = PreferenceManager(context).apply { preferenceDataStore = store }
                val screen = manager.inflateFromResource(context, R.xml.global_preferences, null)
                return requireNotNull(screen.findPreference(Key.HELP_TO_IMPROVE))
            }
            assertTrue(preference().isChecked)
            preference().isChecked = false
            assertFalse(store.getBoolean(Key.HELP_TO_IMPROVE, true))
            assertFalse(preference().isChecked)
            preference().isChecked = true
            assertTrue(preference().isChecked)
        } finally {
            db.close()
        }
    }

    @Test
    fun analyticsUsesAnAllowlistAndHonorsOptOut() {
        var enabled = true
        val config = postHogConfig("phc_example", "https://us.i.posthog.com", analyticsCache, telemetryHttpClient { enabled }) { enabled }
        assertEquals(PersonProfiles.NEVER, config.personProfiles)
        assertFalse(config.preloadFeatureFlags)
        assertFalse(config.sessionReplay)
        assertFalse(config.errorTrackingConfig.autoCapture)
        val filter = config.beforeSendList.single()
        val event = PostHogEvent("app_opened", "random-session", mutableMapOf("profile" to "private", "\$ip" to "192.0.2.1"))
        val safe = requireNotNull(filter.run(event))
        assertEquals(event.distinctId, safe.distinctId)
        assertEquals(
            setOf("app_version", "app_build", "android_api", "build_type", "\$geoip_disable", "\$process_person_profile"),
            safe.properties?.keys,
        )
        assertEquals(true, safe.properties?.get("\$geoip_disable"))
        assertEquals(false, safe.properties?.get("\$process_person_profile"))
        assertNull(filter.run(event.copy(event = "\$screen")))
        enabled = false
        assertNull(filter.run(event))
    }

    @Test
    fun postHogCoreSendsFilteredEventsOnAndroid() {
        val enabled = AtomicBoolean(true)
        val sent = CountDownLatch(1)
        var payload = ""
        val requests = CopyOnWriteArrayList<String>()
        val client = telemetryHttpClient(enabled::get).newBuilder().addInterceptor { chain ->
            requests += "${chain.request().method} ${chain.request().url.encodedPath}"
            if (chain.request().method == "POST") {
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                payload = buffer.readUtf8()
                sent.countDown()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val sdk = PostHog.with(postHogConfig("phc_example", "https://example.com", analyticsCache, client, enabled::get))
        try {
            sdk.capture("app_opened", properties = mapOf("profile" to "private_payload"))
            sdk.flush()
            assertTrue("Observed requests: $requests", sent.await(10, TimeUnit.SECONDS))
            assertTrue(payload.contains("app_opened"))
            assertFalse(payload.contains("private_payload"))
        } finally {
            enabled.set(false)
            sdk.optOut()
            client.dispatcher.cancelAll()
            sdk.close()
        }
    }

    @Test
    fun disablingTelemetryBlocksHttpBeforeItReachesTheNetwork() {
        var enabled = true
        var calls = 0
        val client = telemetryHttpClient { enabled }.newBuilder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val request = Request.Builder().url("https://example.com/batch/").build()
        client.newCall(request).execute().close()
        enabled = false
        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
        assertEquals(1, calls)
    }

    @Test
    fun sentryDropsSensitiveFieldsAndBlocksCaptureAndTransportWhenOff() {
        var enabled = true
        val options = sentryOptions("https://public@example.com/1") { enabled }
        assertEquals(false, options.dataCollection.userInfo)
        assertFalse(options.isEnableAutoSessionTracking)
        assertFalse(options.isSendClientReports)
        assertNull(options.cacheDirPath)
        val event = SentryEvent().apply {
            level = SentryLevel.FATAL
            release = "example@1.0"
            user = User().apply { email = "private@example.com" }
            addBreadcrumb(Breadcrumb().apply { message = "private breadcrumb" })
            setExtra("config", "private config")
            setTag("profile", "private profile")
            contexts["vpn"] = "private endpoint"
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "private exception message"
                    stacktrace = SentryStackTrace(
                        listOf(
                            SentryStackFrame().apply {
                                module = "example.Main"
                                function = "connect"
                                filename = "/private/path/Main.kt"
                                lineno = 42
                                vars = mapOf("password" to "private password")
                                contextLine = "private source"
                            },
                        ),
                    )
                },
            )
        }
        val safe = requireNotNull(options.beforeSend?.execute(event, Hint()))
        assertEquals(event.eventId, safe.eventId)
        assertEquals(event.release, safe.release)
        assertEquals(SentryLevel.FATAL, safe.level)
        assertNull(safe.user)
        assertTrue(safe.breadcrumbs.isNullOrEmpty())
        assertTrue(safe.extras.isNullOrEmpty())
        assertNull(safe.contexts["vpn"])
        assertNull(safe.getTag("profile"))
        val exception = requireNotNull(safe.exceptions).single()
        assertEquals("IllegalStateException", exception.type)
        assertNull(exception.value)
        val frame = requireNotNull(exception.stacktrace?.frames).single()
        assertEquals("Main.kt", frame.filename)
        assertEquals(42, frame.lineno)
        assertEquals("connect", frame.function)
        assertNull(frame.vars)
        assertNull(frame.contextLine)
        assertNull(frame.absPath)
        assertTrue(options.transportGate.isConnected)
        enabled = false
        assertNull(options.beforeSend?.execute(event, Hint()))
        assertFalse(options.transportGate.isConnected)
        assertNull(sanitizedCrashEvent(SentryEvent()))
    }

    @Test
    fun sentryWrapsAndRestoresTheExistingCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var delegated = false
        var report: SentryEvent? = null
        val localHandler = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }
        Thread.setDefaultUncaughtExceptionHandler(localHandler)
        try {
            val options = sentryOptions("https://public@example.com/1") { true }
            val filter = requireNotNull(options.beforeSend)
            options.setBeforeSend { event, hint ->
                filter.execute(event, hint).also { report = it }
            }
            options.setTransportFactory { _, _ -> NoOpTransport.getInstance() }
            Sentry.init(options)
            requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
                .uncaughtException(Thread.currentThread(), IllegalStateException("private config"))
            assertTrue(delegated)
            assertNotNull(report)
            assertTrue(requireNotNull(report?.exceptions).all { it.value == null })
            Sentry.close()
            assertSame(localHandler, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Sentry.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun filterFailuresDropEventsInsteadOfSendingUnfilteredData() {
        val failingConsent = { throw IllegalStateException("preference unavailable") }
        val analytics = postHogConfig("phc_example", "https://us.i.posthog.com", analyticsCache, telemetryHttpClient { false }, failingConsent)
        assertNull(analytics.beforeSendList.single().run(PostHogEvent("app_opened", "random-session")))
        val options = sentryOptions("https://public@example.com/1", failingConsent)
        assertNull(options.beforeSend?.execute(SentryEvent(), Hint()))
    }

    @Test
    fun endpointsRequireHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            postHogConfig("phc_example", "http://example.com", analyticsCache, telemetryHttpClient { true }) { true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            postHogConfig("phc_example", "https://user:password@example.com", analyticsCache, telemetryHttpClient { true }) { true }
        }
        assertThrows(IllegalArgumentException::class.java) { sentryOptions("http://public@example.com/1") { true } }
        assertThrows(IllegalArgumentException::class.java) {
            postHogConfig("../other", "https://example.com", analyticsCache, telemetryHttpClient { true }) { true }
        }
    }

    @Test
    fun analyticsDoesNotReuseOldQueueDirectories() {
        val client = telemetryHttpClient { true }
        val first = postHogConfig("phc_example", "https://example.com", analyticsCache, client) { true }
        val second = postHogConfig("phc_example", "https://example.com", analyticsCache, client) { true }
        assertNotEquals(first.storagePrefix, second.storagePrefix)
        assertTrue(requireNotNull(first.storagePrefix).startsWith("${analyticsCache.absolutePath}/"))
    }
}
