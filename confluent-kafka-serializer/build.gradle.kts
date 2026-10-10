plugins {
    id("library-module-conventions")
    id("library-publish-conventions")
    kotlin("plugin.serialization")
}

description = "Avro4k's module to enable (de)serializing kotlin classes and other native types in avro, with a schema registry."

dependencies {
    implementation(project(":core"))
    // CoreBridge.kt converts with its toAvro4k() / toApacheSchema() once core's public API speaks AvroSchema
    implementation(project(":apache-interop"))
    implementation(kotlin("reflect"))

    api(libs.confluent.kafka.avro.serializer) {
        version {
            // AbstractKafkaAvroSerializer.serializeImpl has a breaking change starting from 8.3.0
            // AbstractKafkaAvroSerializer.getDatumReader has a breaking change starting from 8.3.2 where they added a writerSchemaId param
            strictly("[8.3.2,)")
            prefer(libs.confluent.kafka.avro.serializer.get().version!!)
        }
        // the Apache Avro version is the one declared below
        exclude(group = "org.apache.avro")
        exclude(group = "org.apache.commons", module = "commons-compress")
    }
    // Confluent's serializers are built on Apache Avro; core no longer brings it
    api(libs.apache.avro)
    testImplementation(libs.kotest.junit5)
    testImplementation(libs.kotest.core)
    testImplementation(libs.mockk)
}

repositories {
    maven("https://packages.confluent.io/maven")
}