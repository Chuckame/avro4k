package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroProp
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.schema.isFullNameOrAliasMatch
import kotlinx.io.Buffer
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializerOrNull

internal inline fun <reified T : Annotation> SerialDescriptor.findAnnotation() = annotations.firstNotNullOfOrNull { it as? T }

internal inline fun <reified T : Annotation> SerialDescriptor.findAnnotations() = annotations.filterIsInstance<T>()

@PublishedApi
internal inline fun <reified T : Annotation> SerialDescriptor.findElementAnnotation(elementIndex: Int): T? = getElementAnnotations(elementIndex).firstNotNullOfOrNull { it as? T }

internal inline fun <reified T : Annotation> SerialDescriptor.findElementAnnotations(elementIndex: Int) = getElementAnnotations(elementIndex).filterIsInstance<T>()

@OptIn(ExperimentalSerializationApi::class)
internal val SerialDescriptor.nonNullSerialName: String get() = nonNullOriginal.serialName

/**
 * True if the descriptor's serial name or one of its `@AvroAlias`es matches this schema's full name or aliases. A one-line
 * delegate to the common helper, which cannot see the descriptor helpers; inline, so no bound reference is allocated.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun AvroSchema.isFullNameOrAliasMatch(descriptor: SerialDescriptor): Boolean = isFullNameOrAliasMatch(descriptor.nonNullSerialName) { descriptor.aliases }

@InternalAvro4kApi
public fun AvroSchema.isNamedSchema(): Boolean {
    return this is AvroSchema.NamedSchema
}

@InternalAvro4kApi
public val SerialDescriptor.aliases: Set<String>
    get() =
        findAnnotation<AvroAlias>()?.value?.toSet() ?: emptySet()

internal fun SerialDescriptor.getElementAliases(elementIndex: Int): Set<String> =
    findElementAnnotation<AvroAlias>(elementIndex)?.value?.toSet() ?: emptySet()

@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.possibleSerializationSubclasses(serializersModule: SerializersModule): Sequence<SerialDescriptor> {
    return when (this.kind) {
        PolymorphicKind.SEALED ->
            elementDescriptors.asSequence()
                .filter { it.kind == SerialKind.CONTEXTUAL }
                .flatMap { it.elementDescriptors }
                .flatMap { it.possibleSerializationSubclasses(serializersModule) }

        PolymorphicKind.OPEN ->
            serializersModule.getPolymorphicDescriptors(this@possibleSerializationSubclasses).asSequence()
                .flatMap { it.possibleSerializationSubclasses(serializersModule) }

        SerialKind.CONTEXTUAL -> sequenceOf(getNonNullContextualDescriptor(serializersModule))

        else -> sequenceOf(this)
    }
}

/**
 * The descriptor of a contextual type: the one registered in [serializersModule], else the descriptor of the captured
 * class's own serializer (generated or built-in).
 */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
internal fun SerialDescriptor.getNonNullContextualDescriptor(serializersModule: SerializersModule) =
    serializersModule.getContextualDescriptor(this)
        ?: this.capturedKClass?.serializerOrNull()?.descriptor
        ?: throw AvroSerializationException("No descriptor found in serialization context for $this")

/**
 * Returns true if the given content is starting with `"`, {`, `[`, a digit or equals to `null`.
 * It doesn't check if the content is valid json.
 * It skips the whitespaces at the beginning of the content.
 */
internal fun String.isStartingAsJson(): Boolean {
    val i = this.indexOfFirst { !it.isWhitespace() }
    if (i == -1) {
        return false
    }
    val c = this[i]
    return c == '{' || c == '"' || c == '[' || c.isDigit() || this == "null" || this == "true" || this == "false"
}

/**
 * The value of this prop as json: parsed when it looks like json ([isStartingAsJson]), otherwise a json string, as for
 * [com.github.avrokotlin.avro4k.AvroDefault] (but numbers are kept as written, not normalized).
 */
internal val AvroProp.jsonElement: JsonElement
    get() {
        if (value.isStartingAsJson()) {
            return Json.parseToJsonElement(value)
        }
        return JsonPrimitive(value)
    }

@OptIn(UnsafeIoApi::class)
internal fun Buffer(bytes: ByteArray): Buffer =
    Buffer().apply { UnsafeBufferOperations.moveToTail(this, bytes) }