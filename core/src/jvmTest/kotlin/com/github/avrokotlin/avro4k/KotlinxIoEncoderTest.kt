package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoEncoder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import java.io.ByteArrayOutputStream

class KotlinxIoEncoderTest : StringSpec() {
    init {
        "basic string is serialized correctly" {
            val buffer = Buffer()
            val string = "test"
            KotlinxIoEncoder(buffer).writeString(string)
            buffer.readByteArray() shouldBe byteArrayOf(4.zigZagByte()) + string.encodeToByteArray()
        }

        "special chars in string are serialized correctly" {
            val buffer = Buffer()
            val string = "àûTöç"
            KotlinxIoEncoder(buffer).writeString(string)
            buffer.readByteArray() shouldBe byteArrayOf(9.zigZagByte()) + string.encodeToByteArray()
        }

        "every method writes the same bytes as Apache's BinaryEncoder" {
            // Each case writes through the ABI on one side, and makes the matching calls on Apache's encoder on the other
            fun case(
                abiCalls: AvroBinaryEncoder.() -> Unit,
                apacheCalls: org.apache.avro.io.Encoder.() -> Unit,
            ) = abiCalls to apacheCalls

            val cases =
                listOf(
                    case({ writeNull() }, { writeNull() }),
                    case({ writeBoolean(true) }, { writeBoolean(true) }),
                    case({ writeBoolean(false) }, { writeBoolean(false) }),
                    case({ writeInt(Int.MIN_VALUE) }, { writeInt(Int.MIN_VALUE) }),
                    case({ writeLong(Long.MAX_VALUE) }, { writeLong(Long.MAX_VALUE) }),
                    case({ writeFloat(Float.NaN) }, { writeFloat(Float.NaN) }),
                    case({ writeFloat(-3.25f) }, { writeFloat(-3.25f) }),
                    case({ writeDouble(Double.NEGATIVE_INFINITY) }, { writeDouble(Double.NEGATIVE_INFINITY) }),
                    case({ writeDouble(1e-300) }, { writeDouble(1e-300) }),
                    case({ writeString("") }, { writeString("") }),
                    case({ writeString("àûTöç") }, { writeString("àûTöç") }),
                    case({ writeString("x".repeat(200)) }, { writeString("x".repeat(200)) }),
                    case({ writeString("àû".encodeToByteArray()) }, { writeString(Utf8("àû".encodeToByteArray())) }),
                    case({ writeString(ByteArray(0)) }, { writeString(Utf8(ByteArray(0))) }),
                    case({ writeBytes(ByteArray(0)) }, { writeBytes(ByteArray(0)) }),
                    case({ writeBytes(ByteArray(300) { it.toByte() }) }, { writeBytes(ByteArray(300) { it.toByte() }) }),
                    case({ writeFixed(byteArrayOf(1, 2, 3)) }, { writeFixed(byteArrayOf(1, 2, 3)) }),
                    case({ writeEnum(70) }, { writeEnum(70) }),
                    case({ writeIndex(1) }, { writeIndex(1) }),
                    case({
                        writeArrayStart(2)
                        startItem()
                        writeInt(1)
                        startItem()
                        writeInt(2)
                        writeArrayEnd()
                    }, {
                        writeArrayStart()
                        setItemCount(2)
                        startItem()
                        writeInt(1)
                        startItem()
                        writeInt(2)
                        writeArrayEnd()
                    }),
                    case({
                        writeArrayStart(0)
                        writeArrayEnd()
                    }, {
                        writeArrayStart()
                        setItemCount(0)
                        writeArrayEnd()
                    }),
                    case({
                        writeMapStart(1)
                        startItem()
                        writeString("k")
                        writeLong(-1)
                        writeMapEnd()
                    }, {
                        writeMapStart()
                        setItemCount(1)
                        startItem()
                        writeString("k")
                        writeLong(-1)
                        writeMapEnd()
                    }),
                    case({
                        writeMapStart(0)
                        writeMapEnd()
                    }, {
                        writeMapStart()
                        setItemCount(0)
                        writeMapEnd()
                    })
                )
            cases.forEachIndexed { index, (abiCalls, apacheCalls) ->
                val buffer = Buffer()
                KotlinxIoEncoder(buffer).abiCalls()

                val output = ByteArrayOutputStream()
                val apacheEncoder = EncoderFactory.get().directBinaryEncoder(output, null)
                apacheEncoder.apacheCalls()
                apacheEncoder.flush()

                (index to buffer.readByteArray().toList()) shouldBe (index to output.toByteArray().toList())
            }
        }
    }

    private fun Int.zigZagByte() = (this * 2).toByte()
}