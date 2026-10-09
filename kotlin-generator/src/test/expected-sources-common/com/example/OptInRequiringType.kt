package com.example

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Requires an opt-in, so the expected sources using [OptInRequiringType] only compile if they opt in to it. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalCustomApi

/** Opted in to by every generated file of a test case, through the generator-wide opt-ins. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalGlobalApi

@ExperimentalCustomApi
class OptInRequiringType

@ExperimentalCustomApi
class OptInRequiringTypeSerializer : KSerializer<OptInRequiringType> {
    override val descriptor: SerialDescriptor
        get() = TODO("Not yet implemented")

    override fun serialize(encoder: Encoder, value: OptInRequiringType) {
        TODO("Not yet implemented")
    }

    override fun deserialize(decoder: Decoder): OptInRequiringType {
        TODO("Not yet implemented")
    }
}