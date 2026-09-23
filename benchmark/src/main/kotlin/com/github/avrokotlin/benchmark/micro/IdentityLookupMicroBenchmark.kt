@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.benchmark.micro

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/*
 * Decides how `SerializerLocatorMiddleware` (core) finds the few serializers/descriptors it intercepts. Its `apply` runs
 * on every encoded and decoded value, and nearly always *misses* (a Long, a String, a record...), so the miss path is
 * the one that matters. Three candidates:
 *
 * - `IdentityHashMap.get`: what the middleware used before B6. JVM-only, so it cannot move to commonMain as is.
 * - a linear `===` scan over an array ([ScanLookup]): what B6 first replaced it with (`IdentityLookup`, since removed).
 * - a hand-written `when { key === A -> ...; key === B -> ... }` chain: what the middleware uses now.
 *
 * Result (benchmark/README.md, "Results — M2"): the `when` chain is fastest and the scan slowest at every size, so a
 * scan never pays off here. Kept as the record of that decision, and to re-check it if the entries ever grow.
 *
 * Every structure is reached the way production reaches it: from a static final field (the middleware is an `object`),
 * which lets the JIT treat the `when` chain's keys as constants. That is the `when` chain's one structural advantage, so
 * it must not be measured away.
 */

// The entries the middleware intercepts today, in its order.
private val INTERCEPTED: List<KSerializer<*>> =
    listOf(ByteArraySerializer(), Duration.serializer(), Uuid.serializer(), Instant.serializer())

private val K0: Any = INTERCEPTED[0]
private val K1: Any = INTERCEPTED[1]
private val K2: Any = INTERCEPTED[2]
private val K3: Any = INTERCEPTED[3]
private val V0: Any = Any()
private val V1: Any = Any()
private val V2: Any = Any()
private val V3: Any = Any()

private val PRODUCTION_MAP: IdentityHashMap<Any, Any> =
    IdentityHashMap<Any, Any>().apply {
        put(K0, V0)
        put(K1, V1)
        put(K2, V2)
        put(K3, V3)
    }
private val PRODUCTION_SCAN: ScanLookup = ScanLookup(listOf(K0 to V0, K1 to V1, K2 to V2, K3 to V3))

private fun whenLookup(key: Any): Any? =
    when {
        key === K0 -> V0
        key === K1 -> V1
        key === K2 -> V2
        key === K3 -> V3
        else -> null
    }

/** The linear scan B6 briefly used in core (`IdentityLookup`). */
internal class ScanLookup(entries: List<Pair<Any, Any>>) {
    private val keys: Array<Any> = Array(entries.size) { entries[it].first }
    private val values: Array<Any> = Array(entries.size) { entries[it].second }

    operator fun get(key: Any): Any? {
        for (i in keys.indices) {
            if (keys[i] === key) return values[i]
        }
        return null
    }
}

/**
 * Serializers `apply` actually receives: [MISS_PROBES] of them are not intercepted, the rest are, cycled through so the
 * branch predictor cannot learn a single outcome. The miss-only case is [missKey], the dominant production case.
 */
private const val MISS_PROBES = 12

private fun probes(intercepted: List<Any>): Array<Any> {
    val misses: List<Any> =
        listOf(
            Long.serializer(), String.serializer(), Int.serializer(), Double.serializer(),
            Boolean.serializer(), Float.serializer(), Short.serializer(), Byte.serializer(),
            Char.serializer(), Long.serializer(), String.serializer(), Int.serializer(),
        )
    check(misses.size == MISS_PROBES)
    // 16 probes: 12 misses and 4 hits spread over the intercepted entries (first to last).
    val hits = List(4) { intercepted[it * (intercepted.size - 1) / 3] }
    return (misses + hits).shuffled(kotlin.random.Random(42)).toTypedArray()
}

/** The middleware as it is today: 4 entries, each candidate reached through a static final field. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class InterceptionLookupMicroBenchmark {
    /** Not a constant, so the JIT cannot fold the lookup away. */
    @JvmField
    final var missKey: Any = Long.serializer()

    @JvmField
    final var probes: Array<Any> = emptyArray()

    @JvmField
    final var index: Int = 0

    @Setup
    fun setup() {
        probes = probes(listOf(K0, K1, K2, K3))
    }

    private fun nextProbe(): Any {
        val i = index
        index = (i + 1) and 15
        return probes[i]
    }

    @Benchmark
    fun identityHashMapMiss(): Any? = PRODUCTION_MAP[missKey]

    @Benchmark
    fun scanMiss(): Any? = PRODUCTION_SCAN[missKey]

    @Benchmark
    fun whenMiss(): Any? = whenLookup(missKey)

    @Benchmark
    fun identityHashMapMixed(): Any? = PRODUCTION_MAP[nextProbe()]

    @Benchmark
    fun scanMixed(): Any? = PRODUCTION_SCAN[nextProbe()]

    @Benchmark
    fun whenMixed(): Any? = whenLookup(nextProbe())
}

/**
 * Where the linear scan stops being competitive with an `IdentityHashMap`: the same two cases at growing sizes, the
 * extra entries being padding. The `when` chain has no size-generic form, so it is only in the class above.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class IdentityLookupScalingMicroBenchmark {
    @Param("4", "8", "16", "32")
    @JvmField
    final var size: Int = 4

    @JvmField
    final var map: IdentityHashMap<Any, Any> = IdentityHashMap()

    @JvmField
    final var scan: ScanLookup = ScanLookup(emptyList())

    @JvmField
    final var missKey: Any = Long.serializer()

    @JvmField
    final var probes: Array<Any> = emptyArray()

    @JvmField
    final var index: Int = 0

    @Setup
    fun setup() {
        val keys: List<Any> = INTERCEPTED + List(size - INTERCEPTED.size) { Any() }
        val entries = keys.map { it to Any() }
        map = IdentityHashMap<Any, Any>().apply { entries.forEach { (k, v) -> put(k, v) } }
        scan = ScanLookup(entries)
        probes = probes(keys)
    }

    private fun nextProbe(): Any {
        val i = index
        index = (i + 1) and 15
        return probes[i]
    }

    @Benchmark
    fun identityHashMapMiss(): Any? = map[missKey]

    @Benchmark
    fun scanMiss(): Any? = scan[missKey]

    @Benchmark
    fun identityHashMapMixed(): Any? = map[nextProbe()]

    @Benchmark
    fun scanMixed(): Any? = scan[nextProbe()]
}
