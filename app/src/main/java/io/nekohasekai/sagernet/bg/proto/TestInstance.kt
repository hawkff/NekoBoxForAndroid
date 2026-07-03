package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import io.nekohasekai.sagernet.utils.Commandline
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlinx.coroutines.suspendCancellableCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int = suspendCancellableCoroutine { c ->
        processes = GuardedProcessPool {
            Logs.w(it)
            c.tryResumeWithException(it)
        }
        // Close the box/sidecars if the caller cancels while the test is in flight
        // (e.g. the user pressed Stop). This prevents leaking up to
        // connectionTestConcurrent full sing-box instances + plugin sidecars that
        // otherwise run to completion in the background.
        c.invokeOnCancellation {
            try {
                close()
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        runOnDefaultDispatcher {
            use {
                try {
                    init()
                    launch()
                    if (processes.processCount > 0) {
                        // Wait until the external plugin sidecar(s) have actually bound
                        // their loopback SOCKS port before testing, instead of a fixed
                        // 500ms guess that often raced the sidecar (flaky "connection
                        // refused"). strict = true turns a never-bound listener into a
                        // clear error rather than a misleading connection failure.
                        awaitExternalProcessesReady(strict = true)
                    }
                    c.tryResume(Libcore.urlTest(box, link, timeout))
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(Commandline.redactProcessOutput(config.config))
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }
}
