package com.github.avrokotlin.benchmark.micro

/**
 * Harness settings shared by every micro-benchmark in this package.
 *
 * They are deliberately shorter than the end-to-end suite (5x1s warmup + 5x**3s** measurement).
 * These benchmarks are *attribution instruments*: their primary metric is `gc.alloc.rate.norm`,
 * which converges in two or three iterations (see `docs/plans/notes/a2.md`). Measurement is
 * therefore cut to 5x1s, which keeps the whole 39-combination suite around 10 minutes.
 *
 * Warmup is **not** cut: these methods are small enough that 3 warmup iterations left the
 * throughput of the cheapest families swinging by an order of magnitude between iterations, while
 * their bytes/op stayed stable to the milli-byte. Their throughput figures are good enough to spot
 * a 2x regression on the heavier families; they are not baseline-grade numbers and must not be
 * quoted as such - the end-to-end suite owns those.
 *
 * Note that `./gradlew :benchmark:benchmarkAlloc` overrides both values from the command line
 * (`-PallocWarmups` / `-PallocIterations`, defaulting to 2 and 3), so these only apply to the
 * `micro` benchmark configuration and to direct runs of the JMH jar.
 */
internal const val MICRO_WARMUP_ITERATIONS = 5

internal const val MICRO_MEASUREMENT_ITERATIONS = 5
