package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.internal.decodeWithApacheDecoder
import com.github.avrokotlin.avro4k.internal.encodeWithApacheEncoder
import com.github.avrokotlin.avro4k.schema
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Pins the exact sequence of calls that [encodeWithApacheEncoder] and [decodeWithApacheDecoder] make on an Apache
 * `Encoder`/`Decoder` handed in from outside (Confluent, `DataFileWriter`, a `ValidatingEncoder`...).
 *
 * The expected sequences were recorded on the code *before* avro4k owned its binary codec (when the direct encoders
 * and decoders called Apache's `Encoder`/`Decoder` directly), so this test proves that routing those calls through
 * the codec ABI and its Apache adapters changed nothing an external implementation can observe.
 */
internal class ApacheCodecCallSequenceTest : StringSpec({
    val value =
        CallSequenceRecord(
            nullable = null,
            nonNullUnion = "x",
            bool = true,
            int = -3,
            long = 1L shl 40,
            float = 1.5f,
            double = -2.25,
            string = "héllo",
            bytes = byteArrayOf(1, 2, 3),
            emptyBytes = byteArrayOf(),
            fixed = byteArrayOf(9, 8, 7, 6),
            enum = CallSequenceEnum.B,
            list = listOf(1, 2),
            emptyList = emptyList(),
            map = mapOf("k" to 5L),
            nested = CallSequenceNested("n"),
            char = 'c'
        )
    val stringsValue = StringTypedRecord(bytes = "abc".encodeToByteArray(), char = 'z', fixed = "defg".encodeToByteArray())
    val stringsWriterSchema =
        Schema.Parser().parse(
            """
            {"type":"record","name":"StringTypedRecord","fields":[
              {"name":"bytes","type":"string"},
              {"name":"char","type":"string"},
              {"name":"fixed","type":"string"}
            ]}
            """.trimIndent()
        )

    for (validate in listOf(false, true)) {
        val avro = Avro { validateSerialization = validate }

        "encoder call sequence, all types (validate=$validate)" {
            recordEncoding(avro, avro.schema<CallSequenceRecord>(), serializer<CallSequenceRecord>(), value) shouldBe EXPECTED_ENCODE_ALL
        }

        "decoder call sequence, all types (validate=$validate)" {
            val bytes = Avro.encodeToByteArray(avro.schema<CallSequenceRecord>(), serializer<CallSequenceRecord>(), value)
            recordDecoding(avro, avro.schema<CallSequenceRecord>(), serializer<CallSequenceRecord>(), bytes) shouldBe EXPECTED_DECODE_ALL
        }

        "decoder call sequence, skipping every writer field but one (validate=$validate)" {
            val bytes = Avro.encodeToByteArray(avro.schema<CallSequenceRecord>(), serializer<CallSequenceRecord>(), value)
            recordDecoding(avro, avro.schema<CallSequenceRecord>(), serializer<CallSequenceSubset>(), bytes) shouldBe EXPECTED_DECODE_SKIP
        }

        "encoder call sequence, bytes and char as STRING (validate=$validate)" {
            recordEncoding(avro, stringsWriterSchema, serializer<StringTypedRecord>(), stringsValue) shouldBe EXPECTED_ENCODE_STRINGS
        }

        "decoder call sequence, bytes, char and fixed from STRING (validate=$validate)" {
            val bytes = Avro.encodeToByteArray(stringsWriterSchema, serializer<StringTypedRecord>(), stringsValue)
            recordDecoding(avro, stringsWriterSchema, serializer<StringTypedRecord>(), bytes) shouldBe EXPECTED_DECODE_STRINGS
        }
    }
}) {
    @Serializable
    @SerialName("CallSequenceRecord")
    private data class CallSequenceRecord(
        val nullable: String?,
        val nonNullUnion: String?,
        val bool: Boolean,
        val int: Int,
        val long: Long,
        val float: Float,
        val double: Double,
        val string: String,
        val bytes: ByteArray,
        val emptyBytes: ByteArray,
        @AvroFixed(4) val fixed: ByteArray,
        val enum: CallSequenceEnum,
        val list: List<Int>,
        val emptyList: List<String>,
        val map: Map<String, Long>,
        val nested: CallSequenceNested?,
        val char: Char,
    )

    @Serializable
    @SerialName("CallSequenceRecord")
    private data class CallSequenceSubset(val int: Int)

    @Serializable
    @SerialName("CallSequenceNested")
    private data class CallSequenceNested(val s: String)

    @Serializable
    @SerialName("CallSequenceEnum")
    private enum class CallSequenceEnum { A, B }

    @Serializable
    @SerialName("StringTypedRecord")
    private data class StringTypedRecord(
        val bytes: ByteArray,
        val char: Char,
        @Serializable(with = FixedAsBytesSerializer::class) val fixed: ByteArray,
    )

    /** Decodes through `decodeFixed()`, so a STRING writer schema reaches the `GenericData.Fixed` branch. */
    private object FixedAsBytesSerializer : KSerializer<ByteArray> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FixedAsBytes", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: ByteArray,
        ) {
            encoder.encodeSerializableValue(ByteArraySerializer(), value)
        }

        override fun deserialize(decoder: Decoder): ByteArray = (decoder as AvroDecoder).decodeFixed().bytes()
    }
}

private fun <T> recordEncoding(
    avro: Avro,
    writerSchema: Schema,
    serializer: kotlinx.serialization.SerializationStrategy<T>,
    value: T,
): List<String> {
    val encoder = RecordingEncoder()
    avro.encodeWithApacheEncoder(writerSchema, serializer, value, encoder)
    return encoder.calls
}

private fun <T> recordDecoding(
    avro: Avro,
    writerSchema: Schema,
    deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    bytes: ByteArray,
): List<String> {
    val decoder = RecordingDecoder(DecoderFactory.get().binaryDecoder(bytes, null))
    avro.decodeWithApacheDecoder(writerSchema, deserializer, decoder)
    return decoder.calls
}

private class RecordingEncoder : org.apache.avro.io.Encoder() {
    val calls = mutableListOf<String>()
    private val delegate = EncoderFactory.get().directBinaryEncoder(ByteArrayOutputStream(), null)

    override fun flush() {
        calls += "flush"
        delegate.flush()
    }

    override fun writeNull() {
        calls += "writeNull"
        delegate.writeNull()
    }

    override fun writeBoolean(b: Boolean) {
        calls += "writeBoolean($b)"
        delegate.writeBoolean(b)
    }

    override fun writeInt(n: Int) {
        calls += "writeInt($n)"
        delegate.writeInt(n)
    }

    override fun writeLong(n: Long) {
        calls += "writeLong($n)"
        delegate.writeLong(n)
    }

    override fun writeFloat(f: Float) {
        calls += "writeFloat($f)"
        delegate.writeFloat(f)
    }

    override fun writeDouble(d: Double) {
        calls += "writeDouble($d)"
        delegate.writeDouble(d)
    }

    override fun writeString(utf8: Utf8) {
        calls += "writeString(Utf8:$utf8)"
        delegate.writeString(utf8)
    }

    override fun writeString(str: String) {
        calls += "writeString(String:$str)"
        delegate.writeString(str)
    }

    override fun writeString(charSequence: CharSequence) {
        calls += "writeString(CharSequence:$charSequence)"
        delegate.writeString(charSequence)
    }

    override fun writeBytes(bytes: ByteBuffer) {
        calls += "writeBytes(ByteBuffer:${bytes.remaining()})"
        delegate.writeBytes(bytes)
    }

    override fun writeBytes(
        bytes: ByteArray,
        start: Int,
        len: Int,
    ) {
        calls += "writeBytes(${bytes.contentToString()},$start,$len)"
        delegate.writeBytes(bytes, start, len)
    }

    override fun writeFixed(
        bytes: ByteArray,
        start: Int,
        len: Int,
    ) {
        calls += "writeFixed(${bytes.contentToString()},$start,$len)"
        delegate.writeFixed(bytes, start, len)
    }

    override fun writeFixed(bytes: ByteBuffer) {
        calls += "writeFixed(ByteBuffer:${bytes.remaining()})"
        delegate.writeFixed(bytes)
    }

    override fun writeEnum(e: Int) {
        calls += "writeEnum($e)"
        delegate.writeEnum(e)
    }

    override fun writeArrayStart() {
        calls += "writeArrayStart"
        delegate.writeArrayStart()
    }

    override fun setItemCount(itemCount: Long) {
        calls += "setItemCount($itemCount)"
        delegate.setItemCount(itemCount)
    }

    override fun startItem() {
        calls += "startItem"
        delegate.startItem()
    }

    override fun writeArrayEnd() {
        calls += "writeArrayEnd"
        delegate.writeArrayEnd()
    }

    override fun writeMapStart() {
        calls += "writeMapStart"
        delegate.writeMapStart()
    }

    override fun writeMapEnd() {
        calls += "writeMapEnd"
        delegate.writeMapEnd()
    }

    override fun writeIndex(unionIndex: Int) {
        calls += "writeIndex($unionIndex)"
        delegate.writeIndex(unionIndex)
    }
}

private class RecordingDecoder(private val delegate: org.apache.avro.io.Decoder) : org.apache.avro.io.Decoder() {
    val calls = mutableListOf<String>()

    private fun <T> record(
        name: String,
        result: T,
    ): T {
        calls += "$name=$result"
        return result
    }

    override fun readNull() {
        calls += "readNull"
        delegate.readNull()
    }

    override fun readBoolean(): Boolean = record("readBoolean", delegate.readBoolean())

    override fun readInt(): Int = record("readInt", delegate.readInt())

    override fun readLong(): Long = record("readLong", delegate.readLong())

    override fun readFloat(): Float = record("readFloat", delegate.readFloat())

    override fun readDouble(): Double = record("readDouble", delegate.readDouble())

    override fun readString(old: Utf8?): Utf8 = record("readString(Utf8:${old != null})", delegate.readString(old))

    override fun readString(): String = record("readString()", delegate.readString())

    override fun skipString() {
        calls += "skipString"
        delegate.skipString()
    }

    override fun readBytes(old: ByteBuffer?): ByteBuffer {
        val result = delegate.readBytes(old)
        calls += "readBytes(${old != null})=${result.remaining()}"
        return result
    }

    override fun skipBytes() {
        calls += "skipBytes"
        delegate.skipBytes()
    }

    override fun readFixed(
        bytes: ByteArray,
        start: Int,
        length: Int,
    ) {
        delegate.readFixed(bytes, start, length)
        calls += "readFixed(${bytes.size},$start,$length)=${bytes.contentToString()}"
    }

    override fun skipFixed(length: Int) {
        calls += "skipFixed($length)"
        delegate.skipFixed(length)
    }

    override fun readEnum(): Int = record("readEnum", delegate.readEnum())

    override fun readArrayStart(): Long = record("readArrayStart", delegate.readArrayStart())

    override fun arrayNext(): Long = record("arrayNext", delegate.arrayNext())

    override fun skipArray(): Long = record("skipArray", delegate.skipArray())

    override fun readMapStart(): Long = record("readMapStart", delegate.readMapStart())

    override fun mapNext(): Long = record("mapNext", delegate.mapNext())

    override fun skipMap(): Long = record("skipMap", delegate.skipMap())

    override fun readIndex(): Int = record("readIndex", delegate.readIndex())
}

private val EXPECTED_ENCODE_ALL: List<String> =
    listOf(
        "writeIndex(0)",
        "writeNull",
        "writeIndex(1)",
        "writeString(String:x)",
        "writeBoolean(true)",
        "writeInt(-3)",
        "writeLong(1099511627776)",
        "writeFloat(1.5)",
        "writeDouble(-2.25)",
        "writeString(String:héllo)",
        "writeBytes([1, 2, 3],0,3)",
        "writeBytes([],0,0)",
        "writeFixed([9, 8, 7, 6],0,4)",
        "writeEnum(1)",
        "writeArrayStart",
        "setItemCount(2)",
        "startItem",
        "writeInt(1)",
        "startItem",
        "writeInt(2)",
        "writeArrayEnd",
        "writeArrayStart",
        "setItemCount(0)",
        "writeArrayEnd",
        "writeMapStart",
        "setItemCount(1)",
        "startItem",
        "writeString(String:k)",
        "writeLong(5)",
        "writeMapEnd",
        "writeIndex(1)",
        "writeString(String:n)",
        "writeInt(99)"
    )

private val EXPECTED_DECODE_ALL: List<String> =
    listOf(
        "readIndex=0",
        "readNull",
        "readIndex=1",
        "readString()=x",
        "readBoolean=true",
        "readInt=-3",
        "readLong=1099511627776",
        "readFloat=1.5",
        "readDouble=-2.25",
        "readString()=héllo",
        "readBytes(false)=3",
        "readBytes(false)=0",
        "readFixed(4,0,4)=[9, 8, 7, 6]",
        "readEnum=1",
        "readArrayStart=2",
        "readInt=1",
        "readInt=2",
        "arrayNext=0",
        "readArrayStart=0",
        "readMapStart=1",
        "readString()=k",
        "readLong=5",
        "mapNext=0",
        "readIndex=1",
        "readString()=n",
        "readInt=99"
    )

private val EXPECTED_DECODE_SKIP: List<String> =
    listOf(
        "readIndex=0",
        "readNull",
        "readIndex=1",
        "skipString",
        "readBoolean=true",
        "readInt=-3",
        "readLong=1099511627776",
        "readFloat=1.5",
        "readDouble=-2.25",
        "skipString",
        "skipBytes",
        "skipBytes",
        "skipFixed(4)",
        "readEnum=1",
        "skipArray=2",
        "readInt=1",
        "readInt=2",
        "skipArray=0",
        "skipArray=0",
        "skipMap=1",
        "skipString",
        "readLong=5",
        "skipMap=0",
        "readIndex=1",
        "skipString",
        "readInt=99"
    )

private val EXPECTED_ENCODE_STRINGS: List<String> =
    listOf(
        "writeString(Utf8:abc)",
        "writeString(String:z)",
        "writeString(Utf8:defg)"
    )

private val EXPECTED_DECODE_STRINGS: List<String> =
    listOf(
        "readString(Utf8:false)=abc",
        "readString(Utf8:false)=z",
        "readString(Utf8:false)=defg"
    )