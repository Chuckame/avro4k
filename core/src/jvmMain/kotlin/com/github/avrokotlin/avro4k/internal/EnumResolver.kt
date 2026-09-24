package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroEnumDefault
import kotlinx.serialization.descriptors.SerialDescriptor

internal class EnumResolver {
    /**
     * Identity-keyed: generated enum serializers are singletons, so their descriptors are stable instances (M3-06; see
     * `docs/plans/notes/b6.md`). A rare equal-but-distinct instance (e.g. a nullable wrapper reaching the
     * schema visitor) only costs a recomputation and an entry that is dropped once the instance is collected.
     */
    private val defaultIndexCache = WeakIdentityKeyCache<SerialDescriptor, EnumDefault>()

    /**
     * Holds the resolved default element index of an enum descriptor.
     *
     * [index] is a primitive `Int` on purpose: a nullable `Int?` field would be stored boxed for the whole
     * lifetime of the cache entry, so [NO_DEFAULT] is used as the "no default value" sentinel instead.
     */
    private class EnumDefault(
        @JvmField val index: Int,
    )

    fun getDefaultValueIndex(enumDescriptor: SerialDescriptor): Int? {
        // getOrNull first: the getOrPut lambda captures, so it would be allocated on every hit.
        val index =
            (
                defaultIndexCache.getOrNull(enumDescriptor)
                    ?: defaultIndexCache.getOrPut(enumDescriptor) { loadCache(enumDescriptor) }
            ).index
        return if (index == NO_DEFAULT) null else index
    }

    private fun loadCache(enumDescriptor: SerialDescriptor): EnumDefault {
        var foundIndex = NO_DEFAULT
        for (i in 0 until enumDescriptor.elementsCount) {
            if (enumDescriptor.getElementAnnotations(i).any { it is AvroEnumDefault }) {
                if (foundIndex != NO_DEFAULT) {
                    throw UnsupportedOperationException("Multiple default values found in enum $enumDescriptor")
                }
                foundIndex = i
            }
        }
        return EnumDefault(foundIndex)
    }

    private companion object {
        /**
         * Sentinel meaning "this enum has no [AvroEnumDefault] element". Element indexes are always `>= 0`.
         */
        private const val NO_DEFAULT = -1
    }
}