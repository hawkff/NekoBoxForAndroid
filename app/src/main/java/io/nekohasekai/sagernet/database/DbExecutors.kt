package io.nekohasekai.sagernet.database

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared bounded executor for Room query/invalidation runnables. Replaces the previous
 * `setQueryExecutor { GlobalScope.launch { it.run() } }`, which spawned an unbounded number of
 * coroutines on Dispatchers.Default under invalidation storms (no ordering, no backpressure).
 *
 * A small fixed pool with a bounded queue and a CallerRuns rejection policy gives backpressure:
 * if the queue fills, the submitting thread runs the task itself rather than dropping it or
 * letting the queue grow without bound. Threads are daemon + named for diagnostics.
 */
internal object DbExecutors {
    val query: Executor = ThreadPoolExecutor(
        2,
        2,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(256),
        object : ThreadFactory {
            private val n = AtomicInteger(0)
            override fun newThread(r: Runnable) =
                Thread(r, "room-db-${n.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.CallerRunsPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
}
