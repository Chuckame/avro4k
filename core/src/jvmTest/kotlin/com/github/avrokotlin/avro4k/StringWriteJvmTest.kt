package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.codec.StringWriteTest.Companion.STRING_CASES
import com.github.avrokotlin.avro4k.internal.codec.StringWriteTest.Companion.describe
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream

class StringWriteJvmTest : StringSpec() {
    init {
        "every string case is written as Apache's BinaryEncoder writes it" {
            for (value in STRING_CASES) {
                withClue(describe(value)) {
                    ByteArrayEncoder().apply { writeString(value) }.toByteArray() shouldBe apache { writeString(value) }
                }
            }
        }

        "every string case is written by encodeToStream as by encodeToByteArray and Apache wherever it starts against the end of a chunk" {
            val schema = Avro.schema<PrefixedString>()
            for (prefixSize in listOf(0, 1, 200, 240, 250, 251, 252, 253, 254, 255, 256, 500, 70_000)) {
                val prefix = ByteArray(prefixSize) { (it * 7).toByte() }
                for (value in STRING_CASES) {
                    withClue("$prefixSize bytes before ${describe(value)}") {
                        val record = PrefixedString(prefix, value)
                        val expected =
                            apache {
                                writeBytes(prefix)
                                writeString(value)
                            }
                        val stream = ByteArrayOutputStream()
                        Avro.encodeToStream(schema, record, stream)
                        stream.toByteArray() shouldBe expected
                        Avro.encodeToByteArray(schema, record) shouldBe expected
                    }
                }
            }
        }
    }

    @Serializable
    private class PrefixedString(
        val prefix: ByteArray,
        val value: String,
    )

    private fun apache(write: org.apache.avro.io.Encoder.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(output, null)
        encoder.write()
        encoder.flush()
        return output.toByteArray()
    }
}