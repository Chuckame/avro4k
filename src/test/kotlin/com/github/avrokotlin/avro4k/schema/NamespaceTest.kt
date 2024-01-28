package com.github.avrokotlin.avro4k.schema

import com.github.avrokotlin.avro4k.Avro
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

class NamespaceSchemaTest : FunSpec() {
    init {
        test("use package name for top level class") {
            val expected = org.apache.avro.Schema.Parser().parse(javaClass.getResourceAsStream("/top_level_class_namespace.json"))
            val schema = Avro.default.schema(Tau.serializer())
            schema.toString(true) shouldBe expected.toString(true)
        }

        test("use namespace of object for classes defined inside an object") {
            val expected = org.apache.avro.Schema.Parser().parse(javaClass.getResourceAsStream("/top_level_object_namespace.json"))
            val schema = Avro.default.schema(Outer.Inner.serializer())
            schema.toString(true) shouldBe expected.toString(true)
        }

        test("local classes should use the namespace of their parent object package") {

            val expected = org.apache.avro.Schema.Parser().parse(javaClass.getResourceAsStream("/local_class_namespace.json"))
            val schema = Avro.default.schema(NamespaceTestFoo.serializer())
            schema.toString(true) shouldBe expected.toString(true)
        }
    }

    @Serializable
    data class NamespaceTestFoo(val inner: String)
}

@Serializable
data class Tau(val a: String, val b: Boolean)

object Outer {
    @Serializable
    data class Inner(val a: String, val b: Boolean)
}