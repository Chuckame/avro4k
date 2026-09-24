package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * `WeakMap`-backed: the JS engine holds the keys weakly and compares them by identity, so there is nothing to sweep.
 *
 * JS is single-threaded, so no synchronization is needed. Only plain `get`/`set` are used, which every runtime has.
 *
 * A `WeakMap` only accepts objects as keys. Primitive keys (a Kotlin `String`, `Boolean`, `Double`… is a JS primitive)
 * go to a regular, strong `Map` instead: primitives have no identity beyond their value, and cannot be collected
 * from under a map anyway.
 */
@InternalAvro4kApi
public actual class WeakIdentityKeyCache<K : Any, V : Any> actual constructor() : Cache<K, V> {
    private val objectKeys: dynamic = js("new WeakMap()")
    private val primitiveKeys: dynamic = js("new Map()")

    /** Entries ever inserted: a `WeakMap` cannot be counted, and forgets collected keys silently. */
    private var insertions = 0

    actual override fun getOrPut(key: K, compute: () -> V): V {
        getOrNull(key)?.let { return it }
        val value = compute()
        return putIfAbsent(key, value) ?: value
    }

    internal actual fun getOrNull(key: K): V? {
        val existing = mapFor(key).get(key)
        return if (existing === undefined) null else existing.unsafeCast<V>()
    }

    internal actual fun putIfAbsent(key: K, value: V): V? {
        // Single-threaded: nothing can have inserted the key since the caller's getOrNull, except a re-entrant
        // computation of the same key, whose value then wins as on the other platforms.
        getOrNull(key)?.let { return it }
        mapFor(key).set(key, value)
        insertions++
        return null
    }

    internal actual val size: Int get() = insertions

    private fun mapFor(key: K): dynamic = if (key.isJsObject()) objectKeys else primitiveKeys
}

private fun Any.isJsObject(): Boolean {
    val type = jsTypeOf(this)
    return type == "object" || type == "function"
}