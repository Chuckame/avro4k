plugins {
    id("library-module-conventions")
    id("library-publish-conventions")
    // for the test models only
    kotlin("plugin.serialization")
}

description = "Avro4k's bridge between its multiplatform AvroSchema and Apache Avro's Schema (JVM only)."

dependencies {
    api(project(":core"))
    api(libs.apache.avro)

    testImplementation(libs.kotest.junit5)
    testImplementation(libs.kotest.core)
}

tasks.test {
    // The round-trip test covers the schema files of the other modules' test suites
    val schemaDirs = listOf("core/src/jvmTest/resources", "kotlin-generator/src/test/resources").map { rootProject.layout.projectDirectory.dir(it) }
    schemaDirs.forEach { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
    systemProperty("avro4k.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
}