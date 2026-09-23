package com.github.avrokotlin.avro4k.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

class WeakIdentityKeyCacheTest {
    private data class Key(val name: String)

    @Test
    fun `a hit returns the stored value without recomputing it`() {
        val cache = WeakIdentityKeyCache<Key, Any>()
        val key = Key("a")
        var computations = 0

        val value = cache.getOrPut(key) {
            computations++
            Any()
        }
        repeat(100) {
            cache.getOrPut(key) {
                computations++
                Any()
            } shouldBeSameInstanceAs value
        }

        computations shouldBe 1
    }

    @Test
    fun `keys are compared by identity rather than by equality`() {
        val cache = WeakIdentityKeyCache<Key, Any>()
        val first = Key("same")
        val second = Key("same")
        first shouldBe second

        val firstValue = cache.getOrPut(first) { Any() }
        val secondValue = cache.getOrPut(second) { Any() }

        secondValue shouldNotBeSameInstanceAs firstValue
        cache.getOrPut(first) { error("should hit") } shouldBeSameInstanceAs firstValue
        cache.getOrPut(second) { error("should hit") } shouldBeSameInstanceAs secondValue
    }

    @Test
    fun `every key keeps its own value while the cache grows`() {
        val cache = WeakIdentityKeyCache<Key, String>()
        // Far beyond the initial capacity, so that the table is rebuilt several times.
        val keys = List(2_000) { Key("key-$it") }

        keys.forEach { cache.getOrPut(it) { it.name } }

        keys.forEach { key -> cache.getOrPut(key) { error("should hit ${key.name}") } shouldBe key.name }
    }

    @Test
    fun `the computation can itself use the cache`() {
        val cache = WeakIdentityKeyCache<Key, String>()
        val outer = Key("outer")
        val inner = Key("inner")

        val value = cache.getOrPut(outer) { "outer+" + cache.getOrPut(inner) { "inner" } }

        value shouldBe "outer+inner"
        cache.getOrPut(inner) { error("should hit") } shouldBe "inner"
        cache.getOrPut(outer) { error("should hit") } shouldBe "outer+inner"
    }

    @Test
    fun `a failed computation stores nothing`() {
        val cache = WeakIdentityKeyCache<Key, String>()
        val key = Key("a")

        shouldThrow<IllegalStateException> { cache.getOrPut(key) { error("boom") } }

        cache.getOrPut(key) { "computed" } shouldBe "computed"
    }

    @Test
    fun `primitive-like keys are supported`() {
        // On JS a Kotlin String is a JS primitive, which a WeakMap rejects as a key.
        val cache = WeakIdentityKeyCache<String, Int>()
        val key = "key"

        cache.getOrPut(key) { 1 } shouldBe 1
        cache.getOrPut(key) { 2 } shouldBe 1
    }
}