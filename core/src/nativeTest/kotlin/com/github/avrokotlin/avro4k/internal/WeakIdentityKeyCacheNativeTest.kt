package com.github.avrokotlin.avro4k.internal

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test

@OptIn(ObsoleteWorkersApi::class, ExperimentalAtomicApi::class)
class WeakIdentityKeyCacheNativeTest {
    private class Shared(
        val cache: WeakIdentityKeyCache<Any, Any>,
        val keys: List<Any>,
        val computations: AtomicInt,
    )

    @Test
    fun `all workers observe the same value per key under concurrent access`() {
        val shared = Shared(WeakIdentityKeyCache(), List(256) { Any() }, AtomicInt(0))
        val workers = List(8) { Worker.start() }
        try {
            val results =
                workers.map { worker ->
                    worker.execute(TransferMode.SAFE, { shared }) { state ->
                        state.keys.map { key ->
                            state.cache.getOrPut(key) {
                                state.computations.incrementAndFetch()
                                Any()
                            }
                        }
                    }
                }.map { it.result }

            // A lost race may compute a value that is then discarded, but every worker gets the winner's.
            shared.computations.load() shouldBeGreaterThanOrEqual shared.keys.size
            results.forEach { values -> values.indices.forEach { values[it] shouldBeSameInstanceAs results.first()[it] } }
            shared.cache.size shouldBe shared.keys.size
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }
}