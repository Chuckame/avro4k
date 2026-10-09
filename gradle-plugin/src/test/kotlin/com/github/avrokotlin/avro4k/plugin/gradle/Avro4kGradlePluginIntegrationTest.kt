package com.github.avrokotlin.avro4k.plugin.gradle

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class Avro4kGradlePluginIntegrationTest {
    @field:TempDir
    private lateinit var projectDir: File

    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }
    private val buildFile by lazy { projectDir.resolve("build.gradle.kts") }
    private val schemaFile by lazy { projectDir.resolve("schema.avsc") }
    private val generatedSourceFile by lazy { projectDir.resolve("build/generated/sources/avro/main/Schema.kt") }

    @Test
    fun `sources generation can be configured with custom logical types`() {
        settingsFile.writeText("")
        schemaFile.writeText(
            """
            {
                "type" : "record",
                "name" : "Schema",
                "fields" : [
                    {
                        "name" : "uuid",
                        "type" : {"type":"string", "logicalType":"uuid"}
                    },
                    {
                        "name" : "customType",
                        "type" : {"type":"string", "logicalType":"custom-logical-type"}
                    },
                    {
                        "name" : "ignoredType",
                        "type" : {"type":"string", "logicalType":"another-logical-type"}
                    }
                ]
            }
            """.trimIndent()
        )
        buildFile.writeText(
            """
            plugins {
                id("io.github.avro-kotlin")
            }
            avro4k {
                sourcesGeneration {
                    inputSchemas.from(file("schema.avsc"))
                    logicalTypes {
                        // Back to java.util.UUID, the default before kotlin.uuid.Uuid
                        register("uuid").asType("java.util.UUID", "com.github.avrokotlin.avro4k.serializer.UUIDSerializer")
                        register("custom-logical-type").asContextualType("your.OwnType")
                        
                        // Partial mappings are ignored as incomplete
                        register("another-logical-type")
                    }
                }
            }
            """.trimIndent()
        )

        // Run build
        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("generateAvroKotlinSources", "--stacktrace")
                .forwardOutput()
                .withDebug(true)
                .build()!!

        result.shouldHaveTaskSuccess(":generateAvroKotlinSources")

        // Check the generated file contains our custom type. For deeper tests, all is done in kotlin-generator module
        val generatedContent = generatedSourceFile.readText()
        println("Generated file:\n$generatedContent")
        generatedContent shouldContain "import java.util.UUID"
        generatedContent shouldContain "import com.github.avrokotlin.avro4k.serializer.UUIDSerializer"
        generatedContent shouldContain "uuid: @Serializable(with = UUIDSerializer::class) UUID"
        generatedContent shouldNotContain "kotlin.uuid.Uuid"
        generatedContent shouldContain "your.OwnType"
        generatedContent shouldContain "kotlinx.serialization.Contextual"
        generatedContent shouldContain "ignoredType: String"
    }

    @Test
    fun `sources generation can be configured with opt-ins and file annotations`() {
        settingsFile.writeText("")
        schemaFile.writeText(
            """
            {
                "type" : "record",
                "name" : "Schema",
                "fields" : [
                    {
                        "name" : "uuid",
                        "type" : {"type":"string", "logicalType":"uuid"}
                    },
                    {
                        "name" : "customType",
                        "type" : {"type":"string", "logicalType":"custom-logical-type"}
                    },
                    {
                        "name" : "nested",
                        "type" : {"type":"record", "name":"Nested", "fields":[{"name":"plain", "type":"string"}]}
                    }
                ]
            }
            """.trimIndent()
        )
        buildFile.writeText(
            """
            plugins {
                id("io.github.avro-kotlin")
            }
            avro4k {
                sourcesGeneration {
                    inputSchemas.from(file("schema.avsc"))
                    logicalTypes {
                        register("custom-logical-type").requiresOptIn("your.own.OwnTypeApi").asType("your.own.OwnType", "your.own.OwnTypeSerializer")
                    }
                    fileAnnotations {
                        optIn("your.own.ExperimentalApi", "com.github.avrokotlin.avro4k.InternalAvro4kApi")
                        annotation("kotlin.Suppress", "\"RedundantVisibilityModifier\"", "\"UnusedImport\"")
                    }
                }
            }
            """.trimIndent()
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("generateAvroKotlinSources", "--stacktrace")
                .forwardOutput()
                .withDebug(true)
                .build()!!

        result.shouldHaveTaskSuccess(":generateAvroKotlinSources")

        val generatedContent = generatedSourceFile.readText()
        println("Generated file:\n$generatedContent")
        generatedContent shouldContain
            """
            @file:OptIn(
                ExperimentalApi::class,
                ExperimentalAvro4kApi::class,
                ExperimentalUuidApi::class,
                InternalAvro4kApi::class,
                OwnTypeApi::class,
            )
            @file:Suppress(
                "RedundantVisibilityModifier",
                "UnusedImport",
            )
            """.trimIndent()
        generatedContent shouldContain "import your.own.OwnTypeApi"
        generatedContent shouldContain "import kotlin.uuid.ExperimentalUuidApi"
        generatedContent shouldContain "customType: @Serializable(with = OwnTypeSerializer::class) OwnType"

        // The opt-ins of the logical types are only in the files using them
        val nestedContent = projectDir.resolve("build/generated/sources/avro/main/Nested.kt").readText()
        println("Generated nested file:\n$nestedContent")
        nestedContent shouldContain
            """
            @file:OptIn(
                ExperimentalApi::class,
                ExperimentalAvro4kApi::class,
                InternalAvro4kApi::class,
            )
            @file:Suppress(
                "RedundantVisibilityModifier",
                "UnusedImport",
            )
            """.trimIndent()
        nestedContent shouldNotContain "OwnTypeApi"
        nestedContent shouldNotContain "ExperimentalUuidApi"
    }

    fun BuildResult.shouldHaveTaskSuccess(taskPath: String) {
        val task = this.task(taskPath)
        withClue("'$taskPath' task should succeed") {
            task?.outcome shouldBe TaskOutcome.SUCCESS
        }
    }
}