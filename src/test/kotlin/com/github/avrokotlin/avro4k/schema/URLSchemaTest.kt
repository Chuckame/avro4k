@file:UseSerializers(URLSerializer::class)

package com.github.avrokotlin.avro4k.schema

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.serializer.URLSerializer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.net.URL

class URLSchemaTest : FunSpec({

    test("accept URL as String") {

        val schema = Avro.default.schema(Test.serializer())
        val expected = org.apache.avro.Schema.Parser().parse(javaClass.getResourceAsStream("/url.json"))
        schema shouldBe expected
    }

    test("accept nullable URL as String union") {

        val schema = Avro.default.schema(NullableTest.serializer())
        val expected = org.apache.avro.Schema.Parser().parse(javaClass.getResourceAsStream("/url_nullable.json"))
        schema shouldBe expected
    }
}) {
    @Serializable
    data class Test(val b: URL)

    @Serializable
    data class NullableTest(val b: URL?)
}