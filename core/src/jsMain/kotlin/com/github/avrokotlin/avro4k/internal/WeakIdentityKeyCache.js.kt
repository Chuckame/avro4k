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

    actual override fun getOrPut(key: K, compute: () -> V): V {
        val map = if (key.isJsObject()) objectKeys else primitiveKeys
        val existing = map.get(key)
        if (existing !== undefined) return existing.unsafeCast<V>()

        val value = compute()
        map.set(key, value)
        return value
    }
}

private fun Any.isJsObject(): Boolean {
    val type = jsTypeOf(this)
    return type == "object" || type == "function"
}