@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.serializer

import kotlinx.serialization.SerializationException
import kotlin.math.abs
import kotlin.time.Instant

private const val NANOSECONDS_PER_MILLISECOND: Long = 1_000_000L
private const val NANOSECONDS_PER_MICROSECOND: Long = 1_000L
private const val MILLIS_PER_SECOND: Long = 1_000L
private const val MICROS_PER_SECOND: Long = 1_000_000L
private const val NANOSECONDS_PER_SECOND: Long = 1_000_000_000L

internal fun Instant.toExactEpochMillis(): Long =
    toEpochUnits(MILLIS_PER_SECOND, LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS)

internal fun Instant.toExactEpochMicros(): Long =
    toEpochUnits(MICROS_PER_SECOND, LOGICAL_TYPE_NAME_TIMESTAMP_MICROS)

internal fun Instant.toExactEpochNanos(): Long =
    toEpochUnits(NANOSECONDS_PER_SECOND, LOGICAL_TYPE_NAME_TIMESTAMP_NANOS)

private fun Instant.toEpochUnits(
    unitsPerSecond: Long,
    logicalType: String,
): Long {
    try {
        val nanosPerUnit = NANOSECONDS_PER_SECOND / unitsPerSecond
        if (epochSeconds < 0 && nanosecondsOfSecond > 0) {
            val units = multiplyExact(epochSeconds + 1, unitsPerSecond)
            val adjustment = nanosecondsOfSecond / nanosPerUnit - unitsPerSecond
            return addExact(units, adjustment)
        }

        val units = multiplyExact(epochSeconds, unitsPerSecond)
        return addExact(units, nanosecondsOfSecond / nanosPerUnit)
    } catch (e: ArithmeticException) {
        throw SerializationException("$this is out of range for Avro $logicalType", e)
    }
}

internal fun Instant.Companion.fromEpochMicros(micros: Long): Instant =
    fromEpochUnits(micros, MICROS_PER_SECOND, NANOSECONDS_PER_MICROSECOND)

internal fun Instant.Companion.fromEpochNanos(nanos: Long): Instant =
    fromEpochUnits(nanos, NANOSECONDS_PER_SECOND, 1L)

private fun Instant.Companion.fromEpochUnits(
    units: Long,
    unitsPerSecond: Long,
    nanosPerUnit: Long,
): Instant = fromEpochSeconds(
    units.floorDiv(unitsPerSecond),
    units.mod(unitsPerSecond) * nanosPerUnit
)

/** `a * b`, or an [ArithmeticException] on overflow: the algorithm and message of the JVM's `Math.multiplyExact`. */
private fun multiplyExact(
    a: Long,
    b: Long,
): Long {
    val result = a * b
    // Only operands wider than 31 bits can overflow a long product
    if ((abs(a) or abs(b)) ushr 31 != 0L) {
        if ((b != 0L && result / b != a) || (a == Long.MIN_VALUE && b == -1L)) {
            throw ArithmeticException("long overflow")
        }
    }
    return result
}

/** `a + b`, or an [ArithmeticException] on overflow: the algorithm and message of the JVM's `Math.addExact`. */
private fun addExact(
    a: Long,
    b: Long,
): Long {
    val result = a + b
    // Overflows if and only if both operands have the opposite sign of the result
    if ((a xor result) and (b xor result) < 0) {
        throw ArithmeticException("long overflow")
    }
    return result
}