@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.serializer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import kotlin.time.Instant

internal class KotlinInstantExtensionsTest : StringSpec({
    listOf(
        EpochConversion("milliseconds", 1_000L, 1_000_000L) { toExactEpochMillis() },
        EpochConversion("microseconds", 1_000_000L, 1_000L) { toExactEpochMicros() },
        EpochConversion("nanoseconds", 1_000_000_000L, 1L) { toExactEpochNanos() }
    ).forEach { conversion ->
        "convert the minimum representable ${conversion.name} timestamp without intermediate overflow" {
            val instant = Instant.fromEpochSeconds(
                Math.floorDiv(Long.MIN_VALUE, conversion.unitsPerSecond),
                Math.floorMod(Long.MIN_VALUE, conversion.unitsPerSecond) * conversion.nanosPerUnit
            )

            conversion.convert(instant) shouldBe Long.MIN_VALUE
        }

        "convert the maximum representable ${conversion.name} timestamp" {
            val instant = Instant.fromEpochSeconds(
                Math.floorDiv(Long.MAX_VALUE, conversion.unitsPerSecond),
                Math.floorMod(Long.MAX_VALUE, conversion.unitsPerSecond) * conversion.nanosPerUnit
            )

            conversion.convert(instant) shouldBe Long.MAX_VALUE
        }

        "fail on the ${conversion.name} timestamps just outside the representable range, as Math.multiplyExact and addExact did" {
            val justAfterMax =
                Instant.fromEpochSeconds(
                    Math.floorDiv(Long.MAX_VALUE, conversion.unitsPerSecond),
                    Math.floorMod(Long.MAX_VALUE, conversion.unitsPerSecond) * conversion.nanosPerUnit + conversion.nanosPerUnit
                )
            val justBeforeMin =
                Instant.fromEpochSeconds(
                    Math.floorDiv(Long.MIN_VALUE, conversion.unitsPerSecond),
                    Math.floorMod(Long.MIN_VALUE, conversion.unitsPerSecond) * conversion.nanosPerUnit - conversion.nanosPerUnit
                )
            listOf(justAfterMax, justBeforeMin).forEach { instant ->
                val error = shouldThrow<SerializationException> { conversion.convert(instant) }
                error.message shouldStartWith "$instant is out of range for Avro timestamp-"
                error.cause.shouldBeInstanceOf<ArithmeticException>().message shouldBe "long overflow"
            }
        }
    }

    "floor a fractional pre-epoch instant when converting to milliseconds" {
        val instant = Instant.fromEpochSeconds(-1, 123_456_789)

        instant.toExactEpochMillis() shouldBe -877L
    }

    "create instants from the full microsecond timestamp range" {
        listOf(Long.MIN_VALUE, -876_544L, Long.MAX_VALUE).forEach { micros ->
            Instant.fromEpochMicros(micros) shouldBe Instant.fromEpochSeconds(
                Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L
            )
        }
    }

    "create instants from the full nanosecond timestamp range" {
        listOf(Long.MIN_VALUE, -876_543_211L, Long.MAX_VALUE).forEach { nanos ->
            Instant.fromEpochNanos(nanos) shouldBe Instant.fromEpochSeconds(
                Math.floorDiv(nanos, 1_000_000_000L),
                Math.floorMod(nanos, 1_000_000_000L)
            )
        }
    }
})

private data class EpochConversion(
    val name: String,
    val unitsPerSecond: Long,
    val nanosPerUnit: Long,
    val convert: Instant.() -> Long,
)