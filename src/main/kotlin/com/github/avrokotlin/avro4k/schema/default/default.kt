package com.github.avrokotlin.avro4k.schema.default

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule


/*
how to get default value for a field?
first we decode from a given Serializer to create an instance of the class.
for all optional fields (in CompositeDecoder), we know it using [descriptor.isElementOptional(index)]
we won't encode optional fields
 */

internal fun <T> KSerializer<T>.getElementsDefaults(serializersModule: SerializersModule): Map<SerialDescriptor, Map<Int, Any?>> {
    /*
    todo for each optional class field, we need to decode static values to enter in those sub classes to detect their own defaults
     */
    val decoded = deserialize(StaticValueSkippingOptionalsDecoder(serializersModule, SkippingOptionalsDecoderContext(mutableMapOf()), null))
    val defaults = mutableMapOf<SerialDescriptor, MutableMap<Int, Any?>>()
    serialize(OnlyOptionalRootEncoder(serializersModule, defaults), decoded)
    return defaults
}

private data class SkippingOptionalsDecoderContext(
    /**
     * helps to fix recursivity
     * if value false, descriptor is wip
     * if true, descriptor is finished
     */
    val finishedDescriptors: MutableMap<SerialDescriptor, Boolean>
)

/**
 * Helps to decode a class with static values for non-optional fields, while keeping the default values.
 */
private class StaticValueSkippingOptionalsDecoder(
    override val serializersModule: SerializersModule,
    val context: SkippingOptionalsDecoderContext,
    val element: Pair<SerialDescriptor, Int>?
) : Decoder {
    override fun decodeNull() = null
    override fun decodeBoolean() = true
    override fun decodeByte() = 1.toByte()
    override fun decodeChar() = 'a'
    override fun decodeDouble() = 1.toDouble()
    override fun decodeEnum(enumDescriptor: SerialDescriptor) = 0
    override fun decodeFloat() = 1.toFloat()
    override fun decodeShort() = 1.toShort()
    override fun decodeInt() = 1
    override fun decodeLong() = 1L

    // allow decoding null for primitives or enums only. The rest will always be decoded
    @ExperimentalSerializationApi
    override fun decodeNotNullMark() = element?.first?.getElementDescriptor(element.second)?.kind.let { it is PrimitiveKind || it != SerialKind.ENUM }

    override fun decodeString() = "a"

    override fun decodeInline(descriptor: SerialDescriptor) = this

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return super.decodeSerializableValue(deserializer)
    }

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? {
        if (context.finishedDescriptors.containsKey(deserializer.descriptor)) {
            return null // don't go into as this descriptor has been already decoded or in progress
        }
        context.finishedDescriptors[deserializer.descriptor] = false
        return super.decodeNullableSerializableValue(deserializer)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        context.finishedDescriptors[descriptor] = false
        return StaticValueSkippingOptionalsCompositeDecoder(serializersModule, context)
    }
}

private class StaticValueSkippingOptionalsCompositeDecoder(override val serializersModule: SerializersModule, val context: SkippingOptionalsDecoderContext) : CompositeDecoder {
    private var currentElementIndex = -1

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (descriptor.kind == StructureKind.LIST) {
            // in this case, decodeCollectionSize is not called I don't know why, so if the item type is already wip/finished, then ignore it
            if (context.finishedDescriptors.containsKey(descriptor.getElementDescriptor(0)))
                return CompositeDecoder.DECODE_DONE
        }
        if (descriptor.kind == StructureKind.MAP) {
            // in this case, decodeCollectionSize is not called I don't know why, so if the key or value type is already wip/finished, then ignore it
            if (context.finishedDescriptors.containsKey(descriptor.getElementDescriptor(0)) || context.finishedDescriptors.containsKey(descriptor.getElementDescriptor(1)))
                return CompositeDecoder.DECODE_DONE
        }
        currentElementIndex++
        if (currentElementIndex >= descriptor.elementsCount) {
            return CompositeDecoder.DECODE_DONE
        }
        if (descriptor.isElementOptional(currentElementIndex)) {
            return decodeElementIndex(descriptor)
        }
        return currentElementIndex
    }

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int) = 1.toFloat()
    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int) = true
    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int) = 1.toByte()
    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int) = 'a'
    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int) = 1.toDouble()
    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int) = 1
    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int) = 1L
    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int) = 1.toShort()
    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int) = "a"


    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        return StaticValueSkippingOptionalsDecoder(serializersModule, context, descriptor to index)
    }

    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T?>, previousValue: T?): T? {
        if (context.finishedDescriptors.containsKey(deserializer.descriptor)) {
            return null // don't go into as this descriptor has been already decoded or in progress
        }
        return StaticValueSkippingOptionalsDecoder(serializersModule, context, descriptor to index).decodeSerializableValue(deserializer)
    }

    override fun <T> decodeSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T>, previousValue: T?): T {
        return StaticValueSkippingOptionalsDecoder(serializersModule, context, descriptor to index).decodeSerializableValue(deserializer)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        context.finishedDescriptors[descriptor] = true
    }
}

private class OnlyOptionalCompositeEncoder(
    override val serializersModule: SerializersModule,
    private val defaultElementValues: MutableMap<SerialDescriptor, MutableMap<Int, Any?>>,
) : CompositeEncoder {
    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int) = true

    private fun handleOptionalElementValue(descriptor: SerialDescriptor, index: Int, optionalValue: Any?) {
        defaultElementValues.getOrPut(descriptor) { mutableMapOf() }[index] = optionalValue
    }

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        return if (descriptor.isElementOptional(index)) {
            OnlyOptionalEncoder(serializersModule) { handleOptionalElementValue(descriptor, index, it) }
        } else {
            object : AbstractEncoder() {
                override val serializersModule: SerializersModule
                    get() = this@OnlyOptionalCompositeEncoder.serializersModule

                override fun encodeElement(descriptor: SerialDescriptor, index: Int) = false
            }
        }
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    @ExperimentalSerializationApi
    override fun <T : Any> encodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T?) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun <T> encodeSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T) {
        if (descriptor.isElementOptional(index)) {
            handleOptionalElementValue(descriptor, index, value)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class OnlyOptionalEncoder(override val serializersModule: SerializersModule, private val onValue: (Any?) -> Unit) : Encoder {
    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
        TODO("beginCollection")
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        TODO("beginStructure")
    }

    override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) = onValue(value)
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) = onValue(value)
    override fun encodeBoolean(value: Boolean) = onValue(value)
    override fun encodeByte(value: Byte) = onValue(value)
    override fun encodeChar(value: Char) = onValue(value)
    override fun encodeDouble(value: Double) = onValue(value)
    override fun encodeFloat(value: Float) = onValue(value)
    override fun encodeInt(value: Int) = onValue(value)
    override fun encodeLong(value: Long) = onValue(value)
    override fun encodeNull() = onValue(null)
    override fun encodeShort(value: Short) = onValue(value)
    override fun encodeString(value: String) = onValue(value)

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = onValue(enumDescriptor.getElementName(index))
    override fun encodeInline(descriptor: SerialDescriptor) = this
}

private class OnlyOptionalRootEncoder(
    override val serializersModule: SerializersModule,
    private val defaultElementValues: MutableMap<SerialDescriptor, MutableMap<Int, Any?>>,
) : AbstractEncoder() {
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return OnlyOptionalCompositeEncoder(serializersModule, defaultElementValues)
    }
}
