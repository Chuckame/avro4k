package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type

/**
 * An encoder that checks every call against the writer schema of one datum before forwarding it to [encoder], for
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization]: see [WriterSchemaCallValidator] for what is
 * checked. A call that does not match throws a [com.github.avrokotlin.avro4k.AvroValidationException] and is not forwarded.
 *
 * Created for each encoded value; call [checkDatumComplete] once the value is encoded.
 */
internal class ValidatingAvroBinaryEncoder(
    private val encoder: AvroBinaryEncoder,
    writerSchema: AvroSchema,
) : AvroBinaryEncoder() {
    private val validator = WriterSchemaCallValidator(writerSchema, decoding = false)

    /** Checks that the whole datum was written. */
    fun checkDatumComplete() {
        validator.checkDatumComplete()
    }

    override fun writeNull() {
        validator.primitive(Type.NULL, "writeNull()")
        encoder.writeNull()
    }

    override fun writeBoolean(value: Boolean) {
        validator.primitive(Type.BOOLEAN, "writeBoolean()")
        encoder.writeBoolean(value)
    }

    override fun writeInt(value: Int) {
        validator.primitive(Type.INT, "writeInt()")
        encoder.writeInt(value)
    }

    override fun writeLong(value: Long) {
        validator.primitive(Type.LONG, "writeLong()")
        encoder.writeLong(value)
    }

    override fun writeFloat(value: Float) {
        validator.primitive(Type.FLOAT, "writeFloat()")
        encoder.writeFloat(value)
    }

    override fun writeDouble(value: Double) {
        validator.primitive(Type.DOUBLE, "writeDouble()")
        encoder.writeDouble(value)
    }

    override fun writeString(value: String) {
        validator.primitive(Type.STRING, "writeString()")
        encoder.writeString(value)
    }

    override fun writeString(utf8: ByteArray) {
        validator.primitive(Type.STRING, "writeString()")
        encoder.writeString(utf8)
    }

    override fun writeBytes(value: ByteArray) {
        validator.primitive(Type.BYTES, "writeBytes()")
        encoder.writeBytes(value)
    }

    override fun writeFixed(value: ByteArray) {
        validator.fixed(value.size, "writeFixed()")
        encoder.writeFixed(value)
    }

    override fun writeEnum(index: Int) {
        validator.beginEnum("writeEnum()")
        validator.enumIndex(index, "writeEnum()")
        encoder.writeEnum(index)
    }

    override fun writeIndex(index: Int) {
        validator.beginUnion("writeIndex()")
        validator.unionIndex(index, "writeIndex()")
        encoder.writeIndex(index)
    }

    override fun writeArrayStart(count: Long) {
        validator.beginCollection(Type.ARRAY, "writeArrayStart()")
        validator.collectionAnnounced(count, "writeArrayStart()")
        encoder.writeArrayStart(count)
    }

    override fun writeMapStart(count: Long) {
        validator.beginCollection(Type.MAP, "writeMapStart()")
        validator.collectionAnnounced(count, "writeMapStart()")
        encoder.writeMapStart(count)
    }

    override fun startItem() {
        validator.startItem("startItem()")
        encoder.startItem()
    }

    override fun writeArrayEnd() {
        validator.collectionEnd(Type.ARRAY, "writeArrayEnd()")
        encoder.writeArrayEnd()
    }

    override fun writeMapEnd() {
        validator.collectionEnd(Type.MAP, "writeMapEnd()")
        encoder.writeMapEnd()
    }
}