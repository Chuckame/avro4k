package com.github.avrokotlin.avro4k.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Field default numbers are normalized in pure Kotlin, to write the same default in the schema on every platform. The
 * reference is what the JVM wrote before: `BigDecimal(text).stripTrailingZeros()`, printed as a `BigInteger` when the
 * stripped scale is not positive, else by `BigDecimal.toString`.
 */
internal class DecimalNumberNormalizationTest : StringSpec({
    fun reference(text: String): String {
        val number = BigDecimal(text).stripTrailingZeros()
        return if (number.scale() <= 0) number.toBigInteger().toString() else number.toString()
    }

    "normalizes decimal numbers exactly as BigDecimal did" {
        val handPicked =
            listOf(
                "0",
                "-0",
                "0.0",
                "-0.0",
                "00",
                "0e5",
                "1",
                "-1",
                "+1",
                "10",
                "100",
                "1.0",
                "1.50",
                "-1.50",
                "01.10",
                ".5",
                "5.",
                "1e3",
                "1E3",
                "1e+3",
                "1e-3",
                "1.5e3",
                "1.5e-3",
                "12345e-2",
                "0.000001",
                "0.0000001",
                "0.00000012",
                "-0.0000001",
                "123.456e-10",
                "1e-7",
                "10e-8",
                "9223372036854775807",
                "9223372036854775808",
                "-9223372036854775809",
                "123456789012345678901234567890.123456789012345678901234567890",
                "1.7976931348623157E308",
                "4.9E-324",
                "3.4028235E38",
                "1.4E-45",
                "0.1",
                "0.30000000000000004",
                "1000000.0000001",
                "1e10",
                "25e-1",
                "2.5e0"
            )
        val random = Random(42)
        val generated =
            List(2_000) {
                buildString {
                    if (random.nextInt(3) == 0) append('-')
                    repeat(random.nextInt(0, 6)) { append('0') }
                    repeat(random.nextInt(1, 12)) { append(random.nextInt(10)) }
                    if (random.nextBoolean()) {
                        append('.')
                        repeat(random.nextInt(0, 12)) { append(random.nextInt(10)) }
                        repeat(random.nextInt(0, 4)) { append('0') }
                    }
                    if (random.nextInt(3) == 0) {
                        append(if (random.nextBoolean()) 'e' else 'E')
                        if (random.nextBoolean()) append(if (random.nextBoolean()) '-' else '+')
                        append(random.nextInt(0, 30))
                    }
                }
            }
        (handPicked + generated).forEach { text ->
            withClue(text) {
                text.normalizeDecimalNumber() shouldBe reference(text)
            }
        }
    }

    "rejects what BigDecimal rejects" {
        listOf("", "-", "+", ".", "e5", "1e", "1e+", "abc", "1.2.3", "--1", "1 ", "0x10", "NaN", "Infinity", "1e99999999999").forEach { text ->
            withClue(text) {
                shouldThrow<NumberFormatException> { BigDecimal(text) }
                shouldThrow<NumberFormatException> { text.normalizeDecimalNumber() }
            }
        }
    }
})