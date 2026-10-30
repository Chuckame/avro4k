package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryWriter

/**
 * Encodes one value written with [currentWriterSchema]: the root of an encode call, or the value of a polymorphic
 * structure, whose encoder keeps it and hands it each next value through [reset].
 */
internal class AvroValueEncoder(
    override var currentWriterSchema: AvroSchema,
    avro: Avro,
    binaryWriter: AvroBinaryWriter,
) : AbstractAvroBinaryEncoder(avro, binaryWriter) {
    /**
     * Makes this encoder encode a next value written with [schema], as a new one would: no union branch selected yet. Its
     * own child encoders are kept, each with its own guard.
     */
    fun reset(schema: AvroSchema): AvroValueEncoder {
        currentWriterSchema = schema
        clearSelectedUnionIndex()
        return this
    }
}