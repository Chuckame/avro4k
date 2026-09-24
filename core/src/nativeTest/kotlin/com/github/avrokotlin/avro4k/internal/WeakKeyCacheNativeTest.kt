package com.github.avrokotlin.avro4k.internal

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test

/** Concurrency smoke tests of the equality-mode table and of [IdentityFirstCache] on native threads (M3-06). */
@OptIn(ObsoleteWorkersApi::class, ExperimentalAtomicApi::class)
class WeakKeyCacheNativeTest {
    private data class Key(val id: Int)

    private class Shared(
        val cache: WeakKeyCache<Key, Any>,
        val identityFirst: IdentityFirstCache<Key, Any>,
        val computations: AtomicInt,
    )

    /** Each worker's own instances, returned with its values so that they stay reachable until the assertions. */
    private class WorkerResult(val keys: List<Key>, val values: List<Any>)

    @Test
    fun `all workers observe the same value per key while each uses its own equal instances`() {
        val shared = Shared(WeakKeyCache(), IdentityFirstCache(), AtomicInt(0))
        val results =
            runOnWorkers(shared) { state ->
                val keys = List(KEY_COUNT) { Key(it) }
                WorkerResult(
                    keys,
                    keys.map { key ->
                        state.cache.getOrPut(key) {
                            state.computations.incrementAndFetch()
                            Any()
                        }
                    }
                )
            }

        // A lost race may compute a value that is then discarded, but every worker gets the winner's.
        shared.computations.load() shouldBeGreaterThanOrEqual KEY_COUNT
        results.assertSameValues()
        shared.cache.size shouldBe KEY_COUNT
    }

    @Test
    fun `identity-first only ever seeds canonical instances under concurrent access`() {
        val shared = Shared(WeakKeyCache(), IdentityFirstCache(), AtomicInt(0))
        val results =
            runOnWorkers(shared) { state ->
                val keys = List(KEY_COUNT) { Key(it) }
                repeat(2) { keys.forEach { key -> state.identityFirst.getOrPut(key) { Any() } } }
                WorkerResult(keys, keys.map { key -> state.identityFirst.getOrPut(key) { error("should hit") } })
            }

        results.assertSameValues()
        shared.identityFirst.equalitySize shouldBe KEY_COUNT
        shared.identityFirst.identitySize shouldBeLessThanOrEqual KEY_COUNT
    }

    private fun runOnWorkers(
        shared: Shared,
        job: (Shared) -> WorkerResult,
    ): List<WorkerResult> {
        val workers = List(WORKER_COUNT) { Worker.start() }
        try {
            return workers.map { worker -> worker.execute(TransferMode.SAFE, { shared to job }) { (state, run) -> run(state) } }
                .map { it.result }
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }

    private fun List<WorkerResult>.assertSameValues() {
        val first = first().values
        forEach { result -> result.values.indices.forEach { result.values[it] shouldBeSameInstanceAs first[it] } }
        forEach { it.keys.size shouldBe KEY_COUNT }
    }

    private companion object {
        const val KEY_COUNT = 256
        const val WORKER_COUNT = 8
    }
}