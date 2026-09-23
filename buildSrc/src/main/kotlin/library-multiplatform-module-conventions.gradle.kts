plugins {
    kotlin("multiplatform")
    // HTML only: `dokka-javadoc` is JVM-only, so it stays in `library-module-conventions`.
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("com.diffplug.spotless")
}

apiValidation {
    nonPublicMarkers += "com.github.avrokotlin.avro4k.InternalAvro4kApi"
}

kotlin {
    explicitApi()

    compilerOptions {
        optIn = listOf(
            "com.github.avrokotlin.avro4k.InternalAvro4kApi",
            "com.github.avrokotlin.avro4k.ExperimentalAvro4kApi",
        )
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(11)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    val ktlintVersion = versionCatalogs.named("libs").findVersion("ktlint").get().toString()
    kotlin {
        // Spotless only infers the Kotlin sources of JVM projects: under multiplatform its default target is empty.
        target("src/**/*.kt")
        ktlint(ktlintVersion).setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        ktlint(ktlintVersion).setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

repositories {
    mavenCentral()
}
