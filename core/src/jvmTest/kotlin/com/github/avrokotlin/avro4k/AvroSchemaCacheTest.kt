package com.github.avrokotlin.avro4k

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Regression tests for `Avro.schemaCache`, an identity-first cache backed by an equality lookup (M3-06).
 *
 * `serializer<List<Foo>>().descriptor` is a fresh instance on every call (`docs/plans/notes/b6.md`). Such an instance must
 * hit through equality — same schema instance, no re-inference — and must not be remembered by identity, or the identity
 * layer would grow by one entry per call and pay its O(capacity) copy-on-write insertion every time.
 */
internal class AvroSchemaCacheTest : StringSpec({
    "fresh collection descriptors keep returning the same schema without growing the identity layer" {
        val avro = Avro {}
        val first = serializer<List<CachedRecord>>().descriptor
        val schema = avro.apacheSchema(first)
        val identitySizeAfterFirstCall = avro.schemaCache.identitySize
        val equalitySizeAfterFirstCall = avro.schemaCache.equalitySize

        // Held strongly: seeding them — the bug this guards against — then cannot be hidden by the garbage collector.
        val fresh = List(1_000) { serializer<List<CachedRecord>>().descriptor }
        fresh.forEach { it shouldNotBeSameInstanceAs first }
        fresh.forEach { avro.apacheSchema(it) shouldBeSameInstanceAs schema }

        avro.schemaCache.identitySize shouldBe identitySizeAfterFirstCall
        avro.schemaCache.equalitySize shouldBe equalitySizeAfterFirstCall
        avro.apacheSchema(first) shouldBeSameInstanceAs schema // keeps `first` reachable up to here
    }

    "a stable descriptor hits by identity" {
        val avro = Avro {}
        val descriptor = CachedRecord.serializer().descriptor
        val schema = avro.apacheSchema(descriptor)

        repeat(100) { avro.apacheSchema(CachedRecord.serializer().descriptor) shouldBeSameInstanceAs schema }
        avro.schemaCache.identity.getOrNull(descriptor) shouldBeSameInstanceAs schema
    }
}) {
    @Serializable
    private data class CachedRecord(val value: Int)
}