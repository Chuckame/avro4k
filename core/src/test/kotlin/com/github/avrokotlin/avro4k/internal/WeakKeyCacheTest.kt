package com.github.avrokotlin.avro4k.internal

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The stale-entry purge moved from every [WeakKeyCache.getOrPut] call to the miss/put path only.
 * These check that the observable contract — compute once per distinct key, hits keep returning the
 * stored value — is unchanged, including while entries are being reclaimed in the background.
 */
internal class WeakKeyCacheTest : StringSpec({
    "computes a value once per distinct key and returns it on every hit" {
        val cache = WeakKeyCache<String, Any>()
        val computations = AtomicInteger()
        val compute = {
            computations.incrementAndGet()
            Any()
        }

        val a = cache.getOrPut("a", compute)
        val b = cache.getOrPut("b", compute)

        computations.get() shouldBe 2
        repeat(1_000) {
            cache.getOrPut("a", compute) shouldBeSameInstanceAs a
            cache.getOrPut("b", compute) shouldBeSameInstanceAs b
        }
        computations.get() shouldBe 2
    }

    "keeps live entries while short-lived keys are being collected" {
        // StringBuilder does not override equals/hashCode, so every throw-away key is a distinct entry.
        val cache = WeakKeyCache<StringBuilder, String>()
        val liveKey = StringBuilder("live")
        val liveValue = cache.getOrPut(liveKey) { "live-value" }

        // Churn through throw-away keys so that the put path keeps draining the reference queue.
        repeat(2_000) { index ->
            cache.getOrPut(StringBuilder("throw-away-$index")) { index.toString() }
            if (index % 500 == 0) {
                System.gc()
            }
        }

        cache.getOrPut(liveKey) { error("should not recompute a live entry") } shouldBeSameInstanceAs liveValue
    }

    "computes once per key under concurrent access" {
        val cache = WeakKeyCache<Int, Any>()
        val computations = AtomicInteger()
        val keys = (0 until 64).toList()
        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val results =
                pool.invokeAll(
                    (0 until threadCount).map {
                        Callable {
                            barrier.await(30, TimeUnit.SECONDS)
                            keys.associateWith { key ->
                                cache.getOrPut(key) {
                                    computations.incrementAndGet()
                                    Any()
                                }
                            }
                        }
                    }
                ).map { it.get() }

            computations.get() shouldBe keys.size
            results.forEach { it shouldBe results.first() }
        } finally {
            pool.shutdownNow()
        }
    }
})