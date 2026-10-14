plugins {
    id("java-platform")
    id("library-publish-conventions")
}

description = "BOM for avro4k related modules and dependencies, ensuring compatible versions"

dependencies {
    constraints {
        api(libs.kotlinx.serialization.core) {
            version {
                // Versions < 1.7.0 are missing a new method: SerialDescriptorsKt.getNonNullOriginal
                reject("(,1.7.0[")
            }
        }
        api(project(":core"))
        // Maven users depend on the JVM artifact of core, that has the classes: the BOM manages its version too.
        // Gradle users keep depending on `avro4k-core`, which resolves to the right platform artifact by itself.
        api("${project.group}:avro4k-core-jvm:${project.version}")
        api(project(":apache-interop"))
        api(project(":kotlin-generator"))
        api(project(":confluent-kafka-serializer"))
    }
}
