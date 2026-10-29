package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.decoder.AbstractPolymorphicDecoder
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.types
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
    private val binaryDecoder: AvroBinaryDecoder,
) : AbstractPolymorphicDecoder(avro, descriptor, schema) {
    /**
     * Whether a value is begun on this decoder and not ended yet: from its creation or [reset] to its [endStructure].
     * A flag rather than a position index, which this decoder does not have: the JVM lays it out in what would
     * otherwise be padding.
     */
    var inUse: Boolean = true
        private set

    /** Created for the first value, then handed each next one. */
    private var valueDecoder: AvroValueDirectDecoder? = null

    /*
     * The serial names this decoder found, up to four, each with the descriptor and the writer schema branch it was
     * found for. A list of values of a few subclasses, or a record's polymorphic fields, then find their serial name with
     * two identity comparisons per entry, without the resolver's lookup of the descriptor's names and the name maps.
     *
     * Compared by identity: a descriptor or a branch equal to a remembered one but another instance is looked up, as a
     * new decoder would. The serial name only depends on the descriptor and the branch (the [Avro] instance is the same
     * for the whole call), so a remembered one is the name the lookup would find.
     *
     * Once the four are taken, each next name found replaces the last one: the first three stay, so values cycling
     * through more than four subclasses still find most of their names here, where replacing the oldest would find none.
     *
     * Per-call state, as the decoder itself: nothing is shared between calls. Fields rather than arrays, which would
     * cost three more objects per decoder.
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

    override fun reset(
        descriptor: SerialDescriptor,
        schema: AvroSchema,
    ) {
        super.reset(descriptor, schema)
        inUse = true
    }

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
        val branches = union.types
        val index = binaryDecoder.readIndex()
        if (index < 0 || index >= branches.size) throw unionIndexOutOfRangeError(index, union)
        return branches[index]
    }

    override fun newDecoder(chosenSchema: AvroSchema): Decoder {
        return valueDecoder?.reset(chosenSchema)
            ?: AvroValueDirectDecoder(chosenSchema, avro, binaryDecoder).also { valueDecoder = it }
    }
}