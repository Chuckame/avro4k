package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

@InternalAvro4kApi
public interface Cache<K : Any, V : Any> {
    /**
     * Returns the cached value, or computes, stores, and returns it.
     *
     * The value can be re-computed at any time.
     */
    public fun getOrPut(key: K, compute: () -> V): V
}