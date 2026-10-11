package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.internal.decodeWithApacheDecoder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.builtins.serializer
import org.apache.avro.AvroRuntimeException
import org.apache.avro.Schema
import org.apache.avro.io.DecoderFactory
import java.io.EOFException

/**
 * Bytes an Apache decoder finds malformed fail as an [AvroMalformedDataException], the Apache exception as the cause,
 * like the bytes avro4k's own decoder reads.
 */
internal class ApacheDecoderMalformedDataTest : StringSpec({
    val stringSchema = Schema.create(Schema.Type.STRING)

    "a truncated input is malformed" {
        // a string of 5 bytes, only 2 of them present
        val decoder = DecoderFactory.get().binaryDecoder(byteArrayOf(10, 'a'.code.toByte(), 'b'.code.toByte()), null)

        shouldThrow<AvroMalformedDataException> { Avro.decodeWithApacheDecoder(stringSchema, String.serializer(), decoder) }
            .cause.shouldBeInstanceOf<EOFException>()
    }

    "a negative length is malformed" {
        // zig-zag 0x7B is -62, the length of #269
        val decoder = DecoderFactory.get().binaryDecoder(byteArrayOf(0x7B), null)

        val error = shouldThrow<AvroMalformedDataException> { Avro.decodeWithApacheDecoder(stringSchema, String.serializer(), decoder) }
        error.cause.shouldBeInstanceOf<AvroRuntimeException>()
        error.message shouldBe "Malformed data. Length is negative: -62"
    }

    "an over-long varint is malformed" {
        val decoder = DecoderFactory.get().binaryDecoder(ByteArray(11) { 0xFF.toByte() }, null)

        shouldThrow<AvroMalformedDataException> { Avro.decodeWithApacheDecoder(Schema.create(Schema.Type.LONG), Long.serializer(), decoder) }
    }
})