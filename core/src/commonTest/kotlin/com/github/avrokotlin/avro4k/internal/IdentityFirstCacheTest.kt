package com.github.avrokotlin.avro4k.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

class IdentityFirstCacheTest {
    /**
     * Equal by [name], like two fresh `serializer<List<Foo>>().descriptor`s; counts every `hashCode`/`equals` call, which
     * only the equality layer makes (the identity layer uses identity hash codes and `===`).
     */
    private class Key(val name: String) {
        override fun hashCode(): Int {
            equalityCalls++
            return name.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            equalityCalls++
            return other is Key && other.name == name
        }
    }

    @Test
    fun `an identity hit returns without touching the equality layer`() {
        val cache = IdentityFirstCache<Key, Any>()
        val key = Key("a")
        val value = cache.getOrPut(key) { Any() }

        equalityCalls = 0
        repeat(100) {
            cache.getOrPut(key) { error("should hit") } shouldBeSameInstanceAs value
        }

        equalityCalls shouldBe 0
    }

    @Test
    fun `the canonical instance is seeded into the identity layer`() {
        val cache = IdentityFirstCache<Key, Any>()
        val key = Key("a")

        val value = cache.getOrPut(key) { Any() }

        cache.identitySize shouldBe 1
        cache.identity.getOrNull(key) shouldBeSameInstanceAs value
        cache.equality.getOrNull(key) shouldBeSameInstanceAs value
    }

    @Test
    fun `an equal-but-distinct key hits through equality and is not seeded`() {
        val cache = IdentityFirstCache<Key, Any>()
        val canonical = Key("a")
        val value = cache.getOrPut(canonical) { Any() }
        // Held strongly, so that seeding them — the bug this guards against — could not be hidden by the garbage collector.
        val fresh = List(1_000) { Key("a") }

        equalityCalls = 0
        fresh.forEach { key -> cache.getOrPut(key) { error("an equal key should hit") } shouldBeSameInstanceAs value }

        equalityCalls shouldBeGreaterThanOrEqual fresh.size // the equality layer answered every lookup
        cache.identitySize shouldBe 1
        cache.equalitySize shouldBe 1
        fresh.forEach { cache.identity.getOrNull(it).shouldBeNull() }
        cache.identity.getOrNull(canonical) shouldBeSameInstanceAs value // keeps `canonical` reachable up to here
    }

    @Test
    fun `an instance that loses the equality insertion is not seeded`() {
        val cache = IdentityFirstCache<Key, String>()
        val outer = Key("a")
        val inner = Key("a")

        // The computation inserts an equal key first, as a racing thread would: the outer instance is then not canonical.
        val value = cache.getOrPut(outer) {
            cache.getOrPut(inner) { "inner" }
            "outer"
        }

        value shouldBe "inner"
        cache.identity.getOrNull(outer).shouldBeNull()
        cache.identity.getOrNull(inner) shouldBe "inner"
        cache.identitySize shouldBe 1
    }

    @Test
    fun `values can be recomputed - a failed computation stores nothing`() {
        val cache = IdentityFirstCache<Key, String>()
        val key = Key("a")

        shouldThrow<IllegalStateException> { cache.getOrPut(key) { error("boom") } }
        cache.identitySize shouldBe 0
        cache.equalitySize shouldBe 0

        cache.getOrPut(key) { "computed" } shouldBe "computed"
        cache.getOrPut(key) { error("should hit") } shouldBe "computed"
    }

    @Test
    fun `distinct keys keep their own values while both layers grow`() {
        val cache = IdentityFirstCache<Key, String>()
        val keys = List(2_000) { Key("key-$it") }

        keys.forEach { cache.getOrPut(it) { it.name } }

        keys.forEach { key -> cache.getOrPut(key) { error("should hit ${key.name}") } shouldBe key.name }
        keys.forEach { key -> cache.getOrPut(Key(key.name)) { error("should hit ${key.name}") } shouldBe key.name }
        cache.identitySize shouldBe keys.size
        cache.equalitySize shouldBe keys.size
    }

    private companion object {
        /** Tests of a class run sequentially on every platform, so a plain counter is enough. */
        var equalityCalls = 0
    }
}