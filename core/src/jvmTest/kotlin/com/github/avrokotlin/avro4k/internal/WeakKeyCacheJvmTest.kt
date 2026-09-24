package com.github.avrokotlin.avro4k.internal

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** JVM-only checks of the equality-mode table (M3-06): collectability and concurrency. */
internal class WeakKeyCacheJvmTest : StringSpec({
    "entries whose key was collected are dropped when the table is rebuilt, live ones are kept" {
        val cache = WeakKeyCache<EqualKey, String>()
        val liveKey = EqualKey(-1)
        cache.getOrPut(liveKey) { "live" }

        // Throw-away keys, each distinct by equality and unreachable as soon as getOrPut returns.
        val inserted = 20_000
        repeat(inserted) { index ->
            cache.getOrPut(EqualKey(index)) { index.toString() }
            if (index % 1_000 == 0) System.gc()
        }

        cache.size shouldBeLessThan inserted
        // Looked up with an equal-but-distinct instance: the live entry is still matched by equality.
        cache.getOrPut(EqualKey(-1)) { error("should not recompute a live entry") } shouldBe "live"
        cache.getOrNull(liveKey) shouldBe "live" // keeps `liveKey` reachable up to here
    }

    "all threads observe the same value per key under concurrent access, each with its own equal instances" {
        val cache = WeakKeyCache<EqualKey, Any>()
        val computations = AtomicInteger()
        val keyCount = 256
        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val results =
                pool.invokeAll(
                    List(threadCount) {
                        Callable {
                            // Every thread builds its own instances: only equality can make them share entries.
                            val keys = List(keyCount) { EqualKey(it) }
                            barrier.await(30, TimeUnit.SECONDS)
                            keys.map { key ->
                                cache.getOrPut(key) {
                                    computations.incrementAndGet()
                                    Any()
                                }
                            } to keys
                        }
                    }
                ).map { it.get() }

            // A lost race may compute a value that is then discarded, but every thread gets the winner's.
            computations.get() shouldBeGreaterThanOrEqual keyCount
            val first = results.first().first
            results.forEach { (values, _) -> values.indices.forEach { values[it] shouldBeSameInstanceAs first[it] } }
            cache.size shouldBe keyCount
            results.forEach { (_, keys) -> keys.size shouldBe keyCount } // keeps every thread's keys reachable up to here
        } finally {
            pool.shutdownNow()
        }
    }

    "identity-first: concurrent lookups with fresh instances only ever seed canonical ones" {
        val cache = IdentityFirstCache<EqualKey, Any>()
        val keyCount = 256
        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val results =
                pool.invokeAll(
                    List(threadCount) {
                        Callable {
                            val keys = List(keyCount) { EqualKey(it) }
                            barrier.await(30, TimeUnit.SECONDS)
                            // Twice: the second pass hits, by identity for canonical instances and by equality otherwise.
                            repeat(2) { keys.forEach { key -> cache.getOrPut(key) { Any() } } }
                            keys.map { key -> cache.getOrPut(key) { error("should hit") } } to keys
                        }
                    }
                ).map { it.get() }

            val first = results.first().first
            results.forEach { (values, _) -> values.indices.forEach { values[it] shouldBeSameInstanceAs first[it] } }
            cache.equalitySize shouldBe keyCount
            // One canonical instance per key, whichever thread won; the other 7 × 256 instances are never seeded.
            cache.identitySize shouldBeLessThanOrEqual keyCount
            results.forEach { (_, keys) -> keys.size shouldBe keyCount }
        } finally {
            pool.shutdownNow()
        }
    }
})

/** Equal by [id], never identical: each `EqualKey(n)` is a distinct instance. */
private data class EqualKey(val id: Int)