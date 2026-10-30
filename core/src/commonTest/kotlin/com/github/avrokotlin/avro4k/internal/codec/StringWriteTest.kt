package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.internal.encodeToUtf8
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.test.Test

/**
 * Strings are encoded straight into the encoder's chunks. Every case is checked against a UTF-8 encoder written here
 * from the code points, against [encodeToUtf8], and against the bytes the encoder wrote before, its length then the
 * array from [encodeToUtf8].
 */
class StringWriteTest {
    @Test
    fun `every string case is written as its length then its UTF-8 bytes`() {
        for (value in STRING_CASES) {
            withClue(describe(value)) {
                val expected = expectedString(value)
                ByteArrayBinaryWriter().apply { writeString(value) }.toByteArray() shouldBe expected
                value.encodeToUtf8() shouldBe referenceUtf8(value)
                value.utf8Length() shouldBe referenceUtf8(value).size
                ByteArrayBinaryWriter().apply { writeBytes(value.encodeToUtf8()) }.toByteArray() shouldBe expected
            }
        }
    }

    @Test
    fun `a string of up to 21 chars has a one-byte length whatever its content and 22 chars can need two`() {
        lengthPrefix("€".repeat(20)) shouldBe listOf(60 shl 1)
        lengthPrefix("€".repeat(21)) shouldBe listOf(63 shl 1)
        lengthPrefix("€".repeat(22)) shouldBe listOf(0x84, 0x01)
        lengthPrefix("😀".repeat(10)) shouldBe listOf(40 shl 1)
        lengthPrefix("😀".repeat(10) + "€") shouldBe listOf(43 shl 1)
        lengthPrefix("😀".repeat(11)) shouldBe listOf(44 shl 1)
        lengthPrefix("😀".repeat(16)) shouldBe listOf(0x80, 0x01)
        lengthPrefix("a".repeat(63)) shouldBe listOf(0x7E)
        lengthPrefix("a".repeat(64)) shouldBe listOf(0x80, 0x01)
        lengthPrefix("a".repeat(8191)) shouldBe listOf(0xFE, 0x7F)
        lengthPrefix("a".repeat(8192)) shouldBe listOf(0x80, 0x80, 0x01)
    }

    @Test
    fun `every string case is written whole wherever it starts against the end of a chunk`() {
        // The first chunk is 256 bytes: the string fits, ends exactly at its end, or starts in it and ends in the next,
        // and the chunk may end with no room even for the length. The long cases cross it from fewer offsets.
        val cases = STRING_CASES.map { it to expectedString(it) }
        for (prefix in 180..256) {
            val filler = ByteArray(prefix) { (it * 31).toByte() }
            for ((value, encoded) in cases) {
                if (value.length > 100 && prefix % 8 != 0 && prefix < 253) continue
                withClue("$prefix bytes before ${describe(value)}") {
                    val expected = filler + encoded
                    written(filler) { writeString(value) } shouldBe expected
                    writtenToChunks(filler) { writeString(value) } shouldBe expected
                }
            }
        }
    }

    @Test
    fun `strings of every length up to 300 chars are written whatever their content`() {
        for (length in 0..300) {
            for (pattern in PATTERNS) {
                val value = pattern.take(length)
                withClue(describe(value)) {
                    ByteArrayBinaryWriter().apply { writeString(value) }.toByteArray() shouldBe expectedString(value)
                }
            }
        }
    }

    @Test
    fun `strings of several hundred kilobytes are written across every chunk size`() {
        val values = listOf(MIXED_CONTENT.repeat(5_000), "a".repeat(200_000), "😀".repeat(70_000))
        for (value in values) {
            withClue(describe(value)) {
                val expected = expectedString(value)
                ByteArrayBinaryWriter().apply { writeString(value) }.toByteArray() shouldBe expected
                writtenToChunks(byteArrayOf(1)) { writeString(value) } shouldBe byteArrayOf(1) + expected
            }
        }
        // Many strings in a row cross the chunk ends at every offset, past the largest chunk size
        val many = (0 until 10_000).map { MIXED_CONTENT.take(it % MIXED_CONTENT.length) }
        val expected = many.flatMap { expectedString(it).asList() }.toByteArray()
        ByteArrayBinaryWriter().apply { many.forEach { writeString(it) } }.toByteArray() shouldBe expected
        writtenToChunks(ByteArray(0)) { many.forEach { writeString(it) } } shouldBe expected
    }

    @Test
    fun `the rest of a string crossing the end of a chunk goes to a next chunk sized for that rest`() {
        val chunks = mutableListOf<Pair<Int, Int>>()
        val writeChunk = { array: ByteArray, length: Int -> chunks += array.size to length }
        ByteArrayBinaryWriter(writeChunk)
            .apply {
                writeFixed(ByteArray(200))
                writeString("x".repeat(1_000))
            }.writeChunksTo(writeChunk)
        chunks.size shouldBe 2
        // the first chunk filled but for less than a char
        chunks[0].first shouldBe 256
        (chunks[0].second >= 254) shouldBe true
        // the rest, larger than twice the first chunk, fills a chunk of its size, as the rest of a bytes value does
        chunks[1].first shouldBe chunks[1].second
        chunks[0].second + chunks[1].second shouldBe 200 + 2 + 1_000
    }

    @Test
    fun `strings are written by encodeToByteArray as their length then their UTF-8 bytes`() {
        for (value in STRING_CASES) {
            withClue(describe(value)) {
                Avro.encodeToByteArray(Avro.schema<String>(), value) shouldBe expectedString(value)
            }
        }
        val record = StringsRecord(1, STRING_CASES, "end")
        Avro.encodeToByteArray(Avro.schema<StringsRecord>(), record) shouldBe
            byteArrayOf(2) +
            expectedLength(STRING_CASES.size) +
            STRING_CASES.flatMap { expectedString(it).asList() }.toByteArray() +
            byteArrayOf(0) +
            expectedString("end")
    }

    @Serializable
    private data class StringsRecord(
        val id: Int,
        val values: List<String>,
        val last: String,
    )

    private fun lengthPrefix(value: String): List<Int> {
        val bytes = ByteArrayBinaryWriter().apply { writeString(value) }.toByteArray()
        return bytes.copyOf(bytes.size - referenceUtf8(value).size).map { it.toInt() and 0xff }
    }

    private fun written(
        prefix: ByteArray,
        write: ByteArrayBinaryWriter.() -> Unit,
    ): ByteArray =
        ByteArrayBinaryWriter()
            .apply {
                writeFixed(prefix)
                write()
            }.toByteArray()

    /** Written by an encoder handing each full chunk to a writer, as the JVM's streams do. */
    private fun writtenToChunks(
        prefix: ByteArray,
        write: ByteArrayBinaryWriter.() -> Unit,
    ): ByteArray {
        var written = ByteArray(0)
        val writeChunk = { array: ByteArray, length: Int -> written += array.copyOf(length) }
        ByteArrayBinaryWriter(writeChunk)
            .apply {
                writeFixed(prefix)
                write()
            }.writeChunksTo(writeChunk)
        return written
    }

    companion object {
        private const val MIXED_CONTENT = "aé€😀日\uD800z\uDC00Ω𐀀"

        /** Contents repeated to any length: ASCII, Latin-1, 3-byte, surrogate pairs, mixed with unpaired surrogates. */
        private val PATTERNS =
            listOf(
                "abcdefghij".repeat(30),
                "àéîõü".repeat(60),
                "€日本語".repeat(75),
                "😀𐀀".repeat(75),
                "a😀".repeat(100),
                MIXED_CONTENT.repeat(30),
                "\uD800a\uDC00€".repeat(75)
            )

        /** Every case the bytes are checked for, also from the JVM tests. */
        internal val STRING_CASES: List<String> =
            listOf(
                "",
                "a",
                "avro",
                "àûTöç",
                "ÿ\u0080\u07ff",
                "\u0080\u07ff\u0800a",
                "日本語のテキスト",
                "\uffff\u0800\ud7ff\ue000",
                "😀",
                "😀𐀀🎉\uDBFF\uDFFF",
                // unpaired surrogates at the start, in the middle and at the end
                "\uD800abc",
                "ab\uD800c",
                "abc\uD800",
                "\uDC00abc",
                "ab\uDC00c",
                "abc\uDC00",
                "\uDC00\uD800",
                "\uD800\uD800\uDC00",
                "\uD83D",
                "\uDE00",
                // a surrogate pair at the 21-char boundary of the one-byte length
                "a".repeat(19) + "😀",
                "a".repeat(20) + "😀",
                "a".repeat(20) + "\uD83D",
                "a".repeat(21) + "\uDE00",
                "€".repeat(20) + "😀",
                // 20, 21 and 22 chars of 3-byte and 4-byte content
                "€".repeat(20),
                "€".repeat(21),
                "€".repeat(22),
                "😀".repeat(10),
                "😀".repeat(10) + "€",
                "😀".repeat(11),
                "\uD800".repeat(21),
                // the varint boundaries of the length: 63/64 and 8191/8192 bytes
                "a".repeat(63),
                "a".repeat(64),
                "é".repeat(31) + "a",
                "é".repeat(32),
                "a".repeat(8191),
                "a".repeat(8192),
                "€".repeat(2730) + "a",
                "€".repeat(2730) + "é",
                "😀".repeat(2047) + "€",
                "😀".repeat(2048),
                // longer than the first chunk
                "x".repeat(1_000),
                MIXED_CONTENT.repeat(100)
            )

        fun describe(value: String): String = "a string of ${value.length} chars starting with ${value.take(8)}"

        /** The length as a zig-zag varint, written here bit by bit. */
        fun expectedLength(length: Int): ByteArray {
            val bytes = mutableListOf<Byte>()
            var n = length.toLong() shl 1
            while (n >= 0x80) {
                bytes += ((n and 0x7F) or 0x80).toByte()
                n = n ushr 7
            }
            bytes += n.toByte()
            return bytes.toByteArray()
        }

        fun expectedString(value: String): ByteArray {
            val utf8 = referenceUtf8(value)
            return expectedLength(utf8.size) + utf8
        }

        /** UTF-8 from the code points, `?` for an unpaired surrogate as the JVM writes it. */
        fun referenceUtf8(value: String): ByteArray {
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val codePoint =
                    when {
                        c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                            i++
                            0x10000 + ((c.code - 0xD800) shl 10) + (value[i].code - 0xDC00)
                        }

                        c.isSurrogate() -> '?'.code

                        else -> c.code
                    }
                i++
                when {
                    codePoint < 0x80 -> {
                        bytes += codePoint.toByte()
                    }

                    codePoint < 0x800 -> {
                        bytes += (0xC0 or (codePoint shr 6)).toByte()
                        bytes += (0x80 or (codePoint and 0x3F)).toByte()
                    }

                    codePoint < 0x10000 -> {
                        bytes += (0xE0 or (codePoint shr 12)).toByte()
                        bytes += (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                        bytes += (0x80 or (codePoint and 0x3F)).toByte()
                    }

                    else -> {
                        bytes += (0xF0 or (codePoint shr 18)).toByte()
                        bytes += (0x80 or ((codePoint shr 12) and 0x3F)).toByte()
                        bytes += (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                        bytes += (0x80 or (codePoint and 0x3F)).toByte()
                    }
                }
            }
            return bytes.toByteArray()
        }
    }
}