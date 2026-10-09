package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.decodeFromGenericDataWith
import com.github.avrokotlin.avro4k.encodeToGenericDataWith
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import org.apache.avro.generic.GenericData
import org.apache.avro.util.Utf8

/**
 * A record encoded into generic data is Apache's own `GenericData.Record`, so it can be handed to any Apache API as is,
 * and changed like any other.
 */
internal class GenericDataRecordEncodingTest : StringSpec({
    "encodes a record into an Apache GenericData.Record" {
        val schema = Avro.apacheSchema(Outer.serializer())

        val encoded = Avro.encodeToGenericDataWith(schema, Outer.serializer(), Outer("a", Inner(1), null))

        encoded.shouldBeInstanceOf<GenericData.Record>()
        encoded.schema shouldBe schema
        encoded shouldBe
            GenericData.Record(schema).apply {
                put("name", Utf8("a"))
                put("inner", GenericData.Record(schema.getField("inner").schema()).apply { put("value", 1) })
                put("nullableInner", null)
            }
    }

    "a record encoded into generic data can be changed, then decoded" {
        val schema = Avro.apacheSchema(Outer.serializer())
        val encoded = Avro.encodeToGenericDataWith(schema, Outer.serializer(), Outer("a", Inner(1), null)) as GenericData.Record

        encoded.put("name", "b")

        Avro.decodeFromGenericDataWith(schema, Outer.serializer(), encoded) shouldBe Outer("b", Inner(1), null)
    }
}) {
    @Serializable
    private data class Outer(
        val name: String,
        val inner: Inner,
        val nullableInner: Inner?,
    )

    @Serializable
    private data class Inner(
        val value: Int,
    )
}