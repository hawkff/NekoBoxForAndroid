package io.nekohasekai.sagernet.utils

import android.os.Build
import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Telemetry : OnPreferenceDataStoreChangeListener {
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it, "Telemetry").apply { isDaemon = true } } }

    @Volatile
    private var consent = AtomicBoolean(false)
    private var mainProcess = false
    private var analytics: PostHogInterface? = null
    private var analyticsHttp: OkHttpClient? = null
    private val analyticsCache by lazy { File(app.cacheDir, "telemetry-posthog") }

    fun initialize(isMainProcess: Boolean) {
        if (BuildConfig.POSTHOG_PROJECT_TOKEN.isBlank() && BuildConfig.SENTRY_DSN.isBlank()) return
        mainProcess = isMainProcess
        DataStore.configurationStore.registerChangeListener(this)
        PublicDatabase.database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer("KeyValuePair") {
                override fun onInvalidated(tables: Set<String>) {
                    worker.execute {
                        try {
                            // Settings also change in the other process and during backup restore.
                            DataStore.configurationStore.refreshBlocking()
                            update()
                        } catch (_: Exception) {
                            stop()
                        }
                    }
                }
            },
        )
        onPreferenceDataStoreChanged(DataStore.configurationStore, Key.HELP_TO_IMPROVE)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.HELP_TO_IMPROVE) return
        // Revoke this generation before asynchronous SDK shutdown can flush anything.
        if (!DataStore.helpToImprove) consent.set(false)
        worker.execute { update() }
    }

    private fun update() {
        if (!DataStore.helpToImprove) {
            stop()
            return
        }
        if (consent.get()) return
        stop()
        val permission = AtomicBoolean(true)
        consent = permission
        val allowed = { permission.get() && runCatching { DataStore.helpToImprove }.getOrDefault(false) }

        if (mainProcess && BuildConfig.POSTHOG_PROJECT_TOKEN.isNotBlank()) {
            try {
                analyticsHttp = telemetryHttpClient(allowed)
                analytics = PostHog.with(postHogConfig(BuildConfig.POSTHOG_PROJECT_TOKEN, BuildConfig.POSTHOG_HOST, analyticsCache, analyticsHttp!!, allowed))
                analytics?.capture("app_opened")
            } catch (_: Exception) {
                Logs.w("PostHog initialization failed")
            }
        }
        if (BuildConfig.SENTRY_DSN.isNotBlank()) {
            try {
                // The SDK wraps the existing CrashHandler, which still writes the local report.
                Sentry.init(sentryOptions(BuildConfig.SENTRY_DSN, allowed))
            } catch (_: Exception) {
                Logs.w("Sentry initialization failed")
            }
        }
    }

    private fun stop() {
        consent.set(false)
        analyticsHttp?.dispatcher?.cancelAll()
        analytics?.optOut()
        analytics?.close()
        analytics = null
        analyticsHttp?.connectionPool?.evictAll()
        analyticsHttp = null
        if (mainProcess && !analyticsCache.deleteRecursively()) Logs.w("PostHog cache cleanup failed")
        Sentry.close()
    }
}

internal fun telemetryHttpClient(allowed: () -> Boolean) = OkHttpClient.Builder()
    .addInterceptor { chain ->
        if (!allowed()) throw IOException("Telemetry disabled")
        chain.proceed(chain.request())
    }
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun postHogConfig(token: String, host: String, cache: File, client: OkHttpClient, allowed: () -> Boolean): PostHogConfig {
    val url = requireNotNull(host.toHttpUrlOrNull()) { "Invalid PostHog host" }
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    require(token.isNotBlank() && '/' !in token && '\\' !in token && token != "." && token != "..")
    return PostHogConfig(token, host).apply {
        // The core SDK requires a file queue; a new directory prevents replaying old sessions.
        storagePrefix = File(cache, UUID.randomUUID().toString()).absolutePath
        // beforeSend does not cover SDK configuration requests.
        httpClient = client
        personProfiles = PersonProfiles.NEVER
        preloadFeatureFlags = false
        setDefaultPersonProperties = false
        sendFeatureFlagEvent = false
        sessionReplay = false
        surveys = false
        errorTrackingConfig.autoCapture = false
        maxQueueSize = 20
        addBeforeSend { event ->
            runCatching {
                if (allowed() && event.event == "app_opened") {
                    PostHogEvent(
                        event = event.event,
                        distinctId = event.distinctId,
                        timestamp = event.timestamp,
                        uuid = event.uuid,
                        properties = mutableMapOf(
                            "app_version" to BuildConfig.VERSION_NAME,
                            "app_build" to BuildConfig.VERSION_CODE,
                            "android_api" to Build.VERSION.SDK_INT,
                            "build_type" to BuildConfig.BUILD_TYPE,
                            "\$geoip_disable" to true,
                            "\$process_person_profile" to false,
                        ),
                    )
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}

internal fun sentryOptions(dsn: String, allowed: () -> Boolean): SentryOptions {
    require(dsn.toHttpUrlOrNull()?.isHttps == true) { "Sentry requires an HTTPS DSN" }
    return SentryOptions().apply {
        this.dsn = dsn
        release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        environment = BuildConfig.BUILD_TYPE
        dataCollection.userInfo = false
        isAttachServerName = false
        isAttachThreads = false
        isEnableAutoSessionTracking = false
        isEnableShutdownHook = false
        isSendClientReports = false
        maxBreadcrumbs = 0
        // No disk queue can survive an opt-out or be uploaded by a later launch.
        cacheDirPath = null
        flushTimeoutMillis = 1500
        shutdownTimeoutMillis = 0
        setTransportGate { allowed() }
        setBeforeSend { event, _ ->
            runCatching { if (allowed()) sanitizedCrashEvent(event) else null }.getOrNull()
        }
    }
}

internal fun sanitizedCrashEvent(event: SentryEvent): SentryEvent? {
    val exceptions = event.exceptions?.takeIf { it.isNotEmpty() } ?: return null
    // Copy an allowlist instead of redacting messages that can contain entire VPN configs.
    return SentryEvent(event.timestamp).apply {
        eventId = event.eventId
        level = event.level
        release = event.release
        environment = event.environment
        platform = "java"
        setTag("android_api", Build.VERSION.SDK_INT.toString())
        this.exceptions = exceptions.map { exception ->
            SentryException().apply {
                type = exception.type
                module = exception.module
                mechanism = Mechanism().apply {
                    type = "UncaughtExceptionHandler"
                    isHandled = false
                }
                stacktrace = exception.stacktrace?.let { trace ->
                    SentryStackTrace(
                        trace.frames?.map { frame ->
                            SentryStackFrame().apply {
                                module = frame.module
                                function = frame.function
                                filename = frame.filename?.substringAfterLast('/')?.substringAfterLast('\\')
                                lineno = frame.lineno
                                isInApp = frame.isInApp
                            }
                        },
                    )
                }
            }
        }
    }
}
