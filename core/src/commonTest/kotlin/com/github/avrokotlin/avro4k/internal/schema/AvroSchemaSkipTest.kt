package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.BooleanSchema
import com.github.avrokotlin.avro4k.AvroSchema.BytesSchema
import com.github.avrokotlin.avro4k.AvroSchema.DoubleSchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.FloatSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.LockableList
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoDecoder
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoEncoder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test

/**
 * [skip] consumes exactly one value: each test writes the value with the codec, then a sentinel, skips the value and
 * checks that the sentinel comes next and ends the input. Collections are also written by hand, split into several
 * blocks, including negative-count blocks (count, then the block's byte size, which `skipArray`/`skipMap` jump over).
 */
class AvroSchemaSkipTest {
    private val fixed = FixedSchema(Name("Md5"), 4)
    private val enum = EnumSchema(Name("Suit"), listOf("SPADES", "HEARTS", "CLUBS"))
    private val inner = RecordSchema(Name("Inner"), listOf(RecordSchema.Field("s", StringSchema()), RecordSchema.Field("l", LongSchema())))
    private val outer =
        RecordSchema(
            Name("Outer"),
            listOf(
                RecordSchema.Field("i", IntSchema()),
                RecordSchema.Field("inner", inner),
                RecordSchema.Field("maybe", UnionSchema(NullSchema(), inner)),
                RecordSchema.Field("inners", ArraySchema(inner)),
                RecordSchema.Field("f", fixed)
            )
        )

    private fun assertSkips(
        schema: AvroSchema,
        write: AvroBinaryEncoder.() -> Unit,
    ) {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).apply {
            write()
            writeLong(SENTINEL)
        }
        val decoder = KotlinxIoDecoder(buffer)
        decoder.skip(schema)
        decoder.readLong() shouldBe SENTINEL
        buffer.exhausted() shouldBe true
    }

    private fun bytesOf(write: AvroBinaryEncoder.() -> Unit): ByteArray {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).write()
        return buffer.readByteArray()
    }

    @Test
    fun `skips every primitive type`() {
        assertSkips(NullSchema()) { writeNull() }
        assertSkips(BooleanSchema()) { writeBoolean(true) }
        assertSkips(IntSchema()) { writeInt(Int.MIN_VALUE) }
        assertSkips(IntSchema()) { writeInt(0) }
        assertSkips(LongSchema()) { writeLong(Long.MAX_VALUE) }
        assertSkips(FloatSchema()) { writeFloat(1.5f) }
        assertSkips(DoubleSchema()) { writeDouble(-0.1) }
        assertSkips(StringSchema()) { writeString("héllo wörld") }
        assertSkips(StringSchema()) { writeString("") }
        assertSkips(BytesSchema()) { writeBytes(ByteArray(300) { it.toByte() }) }
        assertSkips(BytesSchema()) { writeBytes(ByteArray(0)) }
    }

    @Test
    fun `skips fixed and enum`() {
        assertSkips(fixed) { writeFixed(byteArrayOf(1, 2, 3, 4)) }
        assertSkips(FixedSchema(Name("Empty"), 0)) { writeFixed(ByteArray(0)) }
        assertSkips(enum) { writeEnum(2) }
    }

    @Test
    fun `skips every branch of a union`() {
        val union = UnionSchema(NullSchema(), IntSchema(), StringSchema(), fixed, enum, inner, ArraySchema(LongSchema()), MapSchema(BytesSchema()))
        assertSkips(union) {
            writeIndex(0)
            writeNull()
        }
        assertSkips(union) {
            writeIndex(1)
            writeInt(42)
        }
        assertSkips(union) {
            writeIndex(2)
            writeString("s")
        }
        assertSkips(union) {
            writeIndex(3)
            writeFixed(byteArrayOf(9, 9, 9, 9))
        }
        assertSkips(union) {
            writeIndex(4)
            writeEnum(1)
        }
        assertSkips(union) {
            writeIndex(5)
            writeString("in")
            writeLong(5)
        }
        assertSkips(union) {
            writeIndex(6)
            writeArrayStart(2)
            startItem()
            writeLong(1)
            startItem()
            writeLong(2)
            writeArrayEnd()
        }
        assertSkips(union) {
            writeIndex(7)
            writeMapStart(1)
            startItem()
            writeString("k")
            writeBytes(byteArrayOf(1))
            writeMapEnd()
        }
    }

    @Test
    fun `skips nested records - arrays and maps written as single blocks`() {
        val writeInner: AvroBinaryEncoder.(String, Long) -> Unit = { s, l ->
            writeString(s)
            writeLong(l)
        }
        assertSkips(outer) {
            writeInt(7)
            writeInner("a", 1)
            writeIndex(1)
            writeInner("b", 2)
            writeArrayStart(2)
            startItem()
            writeInner("c", 3)
            startItem()
            writeInner("d", 4)
            writeArrayEnd()
            writeFixed(byteArrayOf(1, 2, 3, 4))
        }
        assertSkips(MapSchema(ArraySchema(outer))) {
            writeMapStart(1)
            startItem()
            writeString("key")
            writeArrayStart(1)
            startItem()
            writeInt(8)
            writeInner("e", 5)
            writeIndex(0)
            writeNull()
            writeArrayStart(0)
            writeArrayEnd()
            writeFixed(byteArrayOf(5, 6, 7, 8))
            writeArrayEnd()
            writeMapEnd()
        }
    }

    @Test
    fun `skips empty collections`() {
        assertSkips(ArraySchema(IntSchema())) {
            writeArrayStart(0)
            writeArrayEnd()
        }
        assertSkips(MapSchema(IntSchema())) {
            writeMapStart(0)
            writeMapEnd()
        }
    }

    @Test
    fun `skips an array split into several blocks - including negative-count blocks`() {
        val items =
            bytesOf {
                writeString("xy")
                writeString("z")
            }
        assertSkips(ArraySchema(StringSchema())) {
            // a block of 2 items
            writeLong(2)
            writeString("a")
            writeString("bc")
            // a negative-count block: -2 items, then their size in bytes, then the items
            writeLong(-2)
            writeLong(items.size.toLong())
            writeFixed(items)
            // a block of 1 item
            writeLong(1)
            writeString("d")
            // another negative-count block, right before the end
            writeLong(-2)
            writeLong(items.size.toLong())
            writeFixed(items)
            writeLong(0)
        }
    }

    @Test
    fun `skips a map split into several blocks - including negative-count blocks`() {
        val entries =
            bytesOf {
                writeString("k1")
                writeInt(1)
                writeString("k2")
                writeInt(2)
            }
        assertSkips(MapSchema(IntSchema())) {
            writeLong(-2)
            writeLong(entries.size.toLong())
            writeFixed(entries)
            writeLong(1)
            writeString("k3")
            writeInt(3)
            writeLong(2)
            writeString("k4")
            writeInt(4)
            writeString("k5")
            writeInt(5)
            writeLong(0)
        }
    }

    @Test
    fun `skips nested collections split into blocks at both levels`() {
        // [[1, 2], [3]] with the outer list in two blocks and the first inner list in two blocks
        assertSkips(ArraySchema(ArraySchema(IntSchema()))) {
            writeLong(1)
            writeLong(1)
            writeInt(1)
            writeLong(1)
            writeInt(2)
            writeLong(0)
            writeLong(1)
            writeLong(1)
            writeInt(3)
            writeLong(0)
            writeLong(0)
        }
        // {a: {x: 1}, b: {}} with one entry per outer block
        assertSkips(MapSchema(MapSchema(IntSchema()))) {
            writeLong(1)
            writeString("a")
            writeLong(1)
            writeString("x")
            writeInt(1)
            writeLong(0)
            writeLong(1)
            writeString("b")
            writeLong(0)
            writeLong(0)
        }
    }

    @Test
    fun `skips an array of records split into blocks - with nullable union items`() {
        val schema = ArraySchema(UnionSchema(NullSchema(), inner))
        val blockItems =
            bytesOf {
                writeIndex(1)
                writeString("n")
                writeLong(9)
                writeIndex(0)
                writeNull()
            }
        assertSkips(schema) {
            writeLong(1)
            writeIndex(0)
            writeNull()
            writeLong(-2)
            writeLong(blockItems.size.toLong())
            writeFixed(blockItems)
            writeLong(1)
            writeIndex(1)
            writeString("m")
            writeLong(8)
            writeLong(0)
        }
    }

    @Test
    fun `skips a recursive record`() {
        val fields = LockableList<RecordSchema.Field>()
        val node = RecordSchema(Name("Node"), fields)
        fields.add(RecordSchema.Field("value", IntSchema()))
        fields.add(RecordSchema.Field("next", UnionSchema(NullSchema(), node)))
        fields.lock()

        // 1 -> 2 -> 3 -> null
        assertSkips(node) {
            writeInt(1)
            writeIndex(1)
            writeInt(2)
            writeIndex(1)
            writeInt(3)
            writeIndex(0)
            writeNull()
        }
    }

    @Test
    fun `a union branch index out of range fails`() {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).writeIndex(2)
        shouldThrow<IndexOutOfBoundsException> { KotlinxIoDecoder(buffer).skip(UnionSchema(NullSchema(), IntSchema())) }
    }

    private companion object {
        const val SENTINEL = 0x1234_5678_9ABCL
    }
}