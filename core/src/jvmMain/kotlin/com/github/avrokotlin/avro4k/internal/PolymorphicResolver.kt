package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroAlias
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.SerializersModule

internal class PolymorphicResolver(
    private val serializersModule: SerializersModule,
    private val schemaNameResolver: (SerialDescriptor) -> String,
) {
    /**
     * Read per polymorphic value. Identity-first: non-generic sealed descriptors are stable instances, but a generic sealed
     * hierarchy obtained through a fresh top-level serializer is an equal-but-distinct instance per call (M3-06).
     */
    private val cache = IdentityFirstCache<SerialDescriptor, Map<String, String>>()

    fun getFullNamesAndAliasesToSerialName(descriptor: SerialDescriptor): Map<String, String> {
        return cache.getOrPut(descriptor) {
            descriptor.possibleSerializationSubclasses(serializersModule)
                .flatMap {
                    sequence {
                        yield(schemaNameResolver(it) to it.nonNullSerialName)
                        it.findAnnotation<AvroAlias>()?.value?.forEach { alias ->
                            yield(alias to it.nonNullSerialName)
                        }
                    }
                }.toMap()
        }
    }
}