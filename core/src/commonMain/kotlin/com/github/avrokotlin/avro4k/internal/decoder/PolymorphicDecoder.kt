package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryReader
import com.github.avrokotlin.avro4k.internal.schema.asType
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.unionIndexOutOfRangeError
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder

/**
 * Decodes a polymorphic value from the binary input: the serial name of its subclass from the branch of the writer
 * schema it is written with, then its value.
 *
 * The decoder that begins it keeps it, and hands it each next polymorphic value through [reset] once this one is ended.
 * It keeps its value decoder the same way, whose own decoders (the subclass's record decoder, mostly) are then kept too:
 * a list of polymorphic values, or a record's successive polymorphic fields, decode on one decoder of each kind instead
 * of a polymorphic decoder, a value decoder and a record decoder per value.
 */
internal class PolymorphicDecoder(
    avro: Avro,
    descriptor: SerialDescriptor,
    schema: AvroSchema,
    private val binaryReader: AvroBinaryReader,
) : AbstractPolymorphicDecoder(avro, descriptor, schema) {
    /**
     * Whether a value is begun on this decoder and not ended yet: from its creation or [reset] to its [endStructure].
     * A flag rather than a position index, which this decoder does not have: the JVM lays it out in what would
     * otherwise be padding.
     */
    var inUse: Boolean = true
        private set

    /** Created for the first value, then handed each next one. */
    private var valueDecoder: AvroValueDecoder? = null

    /*
     * The last serial names found, by (descriptor, writer branch) compared by identity: a list of values of a few
     * subclasses then skips the resolver's name lookup. Four is not a limit on the union: a fifth subclass is looked up
     * as usual and takes the last slot, so the first three stay. Fields rather than arrays: no extra object per decoder.
     */
    private var descriptor0: SerialDescriptor? = null
    private var branch0: AvroSchema? = null
    private var serialName0: String? = null
    private var descriptor1: SerialDescriptor? = null
    private var branch1: AvroSchema? = null
    private var serialName1: String? = null
    private var descriptor2: SerialDescriptor? = null
    private var branch2: AvroSchema? = null
    private var serialName2: String? = null
    private var descriptor3: SerialDescriptor? = null
    private var branch3: AvroSchema? = null
    private var serialName3: String? = null

    override fun endStructure(descriptor: SerialDescriptor) {
        inUse = false
    }

    /** Reads the branch of a union first: the serial names are remembered by branch. */
    override fun findSerialName(
        descriptor: SerialDescriptor,
        schema: AvroSchema,
    ): String? {
        val branch = if (schema.isUnion) readBranch(schema) else schema
        val serialName = rememberedSerialName(descriptor, branch) ?: return findAndRememberSerialName(descriptor, branch)
        chosenSchema = branch
        return serialName
    }

    private fun rememberedSerialName(
        descriptor: SerialDescriptor,
        branch: AvroSchema,
    ): String? {
        if (descriptor0 === descriptor && branch0 === branch) return serialName0
        if (descriptor1 === descriptor && branch1 === branch) return serialName1
        if (descriptor2 === descriptor && branch2 === branch) return serialName2
        if (descriptor3 === descriptor && branch3 === branch) return serialName3
        return null
    }

    /** Out of the hot path: the first value of each subclass, or a subclass no longer remembered. */
    private fun findAndRememberSerialName(
        descriptor: SerialDescriptor,
        branch: AvroSchema,
    ): String? {
        val serialName = super.findSerialName(descriptor, branch) ?: return null
        when {
            descriptor0 === null -> {
                descriptor0 = descriptor
                branch0 = branch
                serialName0 = serialName
            }

            descriptor1 === null -> {
                descriptor1 = descriptor
                branch1 = branch
                serialName1 = serialName
            }

            descriptor2 === null -> {
                descriptor2 = descriptor
                branch2 = branch
                serialName2 = serialName
            }

            else -> {
                descriptor3 = descriptor
                branch3 = branch
                serialName3 = serialName
            }
        }
        return serialName
    }

    /** Never reached: [findSerialName] reads the branch of a union before looking its name up, and no branch is a union. */
    override fun tryFindSerialNameForUnion(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): String? {
        return tryFindSerialName(namesAndAliasesToSerialName, readBranch(schema))
    }

    private fun readBranch(union: AvroSchema): AvroSchema {
        val branches = union.asType<AvroSchema.UnionSchema>().types
        val index = binaryReader.readIndex()
        if (index !in branches.indices) throw unionIndexOutOfRangeError(index, union)
        return branches[index]
    }

    override fun newDecoder(chosenSchema: AvroSchema): Decoder {
        return valueDecoder?.reset(chosenSchema)
            ?: AvroValueDecoder(chosenSchema, avro, binaryReader).also { valueDecoder = it }
    }

    override fun reset(
        descriptor: SerialDescriptor,
        schema: AvroSchema,
    ) {
        super.reset(descriptor, schema)
        inUse = true
    }
}