package com.github.avrokotlin.avro4k.internal

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class WeakIdentityKeyCacheJvmTest : StringSpec({
    "entries whose key was collected are dropped when the table is rebuilt, live ones are kept" {
        val cache = WeakIdentityKeyCache<Any, String>()
        val liveKey = Any()
        cache.getOrPut(liveKey) { "live" }

        // Throw-away keys, unreachable as soon as getOrPut returns. Collecting between batches lets the rebuilds that
        // a growing table triggers find them stale.
        val inserted = 20_000
        repeat(inserted) { index ->
            cache.getOrPut(Any()) { index.toString() }
            if (index % 1_000 == 0) System.gc()
        }

        cache.size shouldBeLessThan inserted
        cache.getOrPut(liveKey) { error("should not recompute a live entry") } shouldBe "live"
    }

    "all threads observe the same value per key under concurrent access" {
        val cache = WeakIdentityKeyCache<Any, Any>()
        val computations = AtomicInteger()
        val keys = List(256) { Any() }
        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val results =
                pool.invokeAll(
                    List(threadCount) {
                        Callable {
                            barrier.await(30, TimeUnit.SECONDS)
                            keys.map { key ->
                                cache.getOrPut(key) {
                                    computations.incrementAndGet()
                                    Any()
                                }
                            }
                        }
                    }
                ).map { it.get() }

            // A lost race may compute a value that is then discarded, but every thread gets the winner's.
            computations.get() shouldBeGreaterThanOrEqual keys.size
            results.forEach { values -> values.indices.forEach { values[it] shouldBeSameInstanceAs results.first()[it] } }
            keys.forEachIndexed { index, key -> cache.getOrPut(key) { error("should hit") } shouldBeSameInstanceAs results.first()[index] }
            cache.size shouldBe keys.size
        } finally {
            pool.shutdownNow()
        }
    }
})