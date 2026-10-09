package com.github.avrokotlin.avro4k.plugin.gradle

import com.github.avrokotlin.avro4k.FieldNamingStrategy
import com.github.avrokotlin.avro4k.KotlinGenerator
import com.github.avrokotlin.avro4k.generateKotlinClassesFromFiles
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File

public interface Avro4kPluginSourcesGenerationExtension {
    /**
     * Indicates the avro schema files or directories containing schema files.
     * Only files with `.avsc` extension will be processed.
     *
     * By default, this is set to `src/main/avro` directory in the project.
     */
    public val inputSchemas: ConfigurableFileCollection

    /**
     * The output directory where the generated kotlin files will be placed.
     *
     * By default, this is set to `build/generated/sources/avro/main` directory in the project.
     */
    public val outputDir: DirectoryProperty

    /**
     * When true, uses Kotlin naming conventions for generated field names (camelCase).
     * Defaults to false, keeping original avro field names.
     */
    public val useKotlinConventionForFieldNames: Property<Boolean>

    @get:Nested
    public val logicalTypes: Avro4kPluginSourcesGenerationLogicalTypesExtension

    /**
     * Configures the logical types to use in code generation, mapping a logical type name to the class and its serializer (if not contextual).
     * This allows using specific classes for class fields with a given logical type.
     *
     * Any logical type defined in the avro schema that doesn't have a matching entry in this set will be generated as its core type (string, int, ...).
     *
     * Example: given the following logical types:
     * ```
     * avro4k {
     *   sourcesGeneration {
     *     logicalTypes {
     *       register("uuid").asType("java.util.UUID", "com.github.avrokotlin.avro4k.serializer.UUIDSerializer")
     *       register("custom-type").asContextualType("your.FieldType")
     *     }
     *   }
     * }
     * ```
     * And the following Avro schema:
     * ```
     * {
     *   "type": "record",
     *   "name": "MyRecord",
     *   "fields": [
     *     {
     *       "name": "uuidField",
     *       "type": {
     *         "type": "string",
     *         "logicalType": "uuid"
     *       }
     *     },
     *     {
     *       "name": "customField",
     *       "type": {
     *         "type": "string",
     *         "logicalType": "custom-type"
     *       }"
     *     }
     *   ]
     * }
     * ```
     * Then the generated `MyRecord` class will have the following fields:
     * ```
     * @Serializable
     * data class MyRecord(
     *   @Serializable(with = UUIDSerializer::class)
     *   val uuidField: UUID,
     *   @Contextual
     *   val customField: FieldType
     * )
     * ```
     */
    public fun logicalTypes(action: Action<in Avro4kPluginSourcesGenerationLogicalTypesExtension>): Unit =
        action.execute(logicalTypes)

    @get:Nested
    public val fileAnnotations: Avro4kPluginSourcesGenerationFileAnnotationsExtension

    /**
     * Configures the opt-ins and the annotations added to every generated file.
     *
     * Each generated file has a single `@file:OptIn(...)`, with the markers given here, the ones avro4k needs, and the ones required
     * by the logical types the file uses (see [LogicalTypeBuilder.requiresOptIn]).
     *
     * Example:
     * ```
     * avro4k {
     *   sourcesGeneration {
     *     fileAnnotations {
     *       optIn("your.own.ExperimentalApi")
     *       annotation("kotlin.Suppress", "\"RedundantVisibilityModifier\"")
     *     }
     *   }
     * }
     * ```
     * Then every generated file starts with:
     * ```
     * @file:OptIn(ExperimentalApi::class, ExperimentalAvro4kApi::class, InternalAvro4kApi::class)
     * @file:Suppress("RedundantVisibilityModifier")
     * ```
     */
    public fun fileAnnotations(action: Action<in Avro4kPluginSourcesGenerationFileAnnotationsExtension>): Unit =
        action.execute(fileAnnotations)
}

public interface Avro4kPluginSourcesGenerationLogicalTypesExtension {
    public fun register(logicalTypeName: String): LogicalTypeBuilder {
        return LogicalTypeBuilder(logicalTypeName) { k, mapping -> mappings.put(k, mapping) }
    }

    @get:Input
    public val mappings: MapProperty<String, LogicalTypeMapping>
}

public interface Avro4kPluginSourcesGenerationFileAnnotationsExtension {
    /**
     * The fully qualified names of the opt-in marker classes (annotation classes annotated with `@RequiresOptIn`) that every generated
     * file opts in to.
     */
    @get:Input
    public val optIns: SetProperty<String>

    /** The annotations added to every generated file, with the `file` use-site target. */
    @get:Input
    public val annotations: ListProperty<FileAnnotation>

    /**
     * Makes every generated file opt in to the given markers, in its `@file:OptIn(...)`.
     *
     * @param markerClassNames the fully qualified names of the opt-in marker classes
     */
    public fun optIn(vararg markerClassNames: String) {
        optIns.addAll(*markerClassNames)
    }

    /**
     * Adds the annotation to every generated file, as `@file:<annotation simple name>(<arguments>)`.
     * Use [optIn] for `kotlin.OptIn`, as a file can only have one.
     *
     * @param className the fully qualified name of the annotation class, imported in the generated files
     * @param arguments the arguments of the annotation, as Kotlin code written as is (e.g. `"\"a string\""`, `"your.own.Type::class"`, `"level = 2"`)
     */
    public fun annotation(className: String, vararg arguments: String) {
        require(className != "kotlin.OptIn") { "Use optIn(...) to opt in to markers in the generated files, instead of annotation(\"kotlin.OptIn\", ...)" }
        annotations.add(FileAnnotation(className, arguments.toList()))
    }
}

@ConsistentCopyVisibility
public data class LogicalTypeMapping internal constructor(
    val type: String,
    val serializerType: String?,
    val requiredOptIns: List<String> = emptyList(),
) : java.io.Serializable

/** An annotation added to every generated file, see [Avro4kPluginSourcesGenerationFileAnnotationsExtension.annotation]. */
@ConsistentCopyVisibility
public data class FileAnnotation internal constructor(
    val className: String,
    val arguments: List<String>,
) : java.io.Serializable

public class LogicalTypeBuilder internal constructor(
    private val logicalTypeName: String,
    private val whenBuilt: (String, LogicalTypeMapping) -> Unit,
) {
    private var requiredOptIns: List<String> = emptyList()

    /**
     * Declares the opt-in markers that the class of this logical type, or its serializer, requires. The generated files using this
     * logical type opt in to them, in their `@file:OptIn(...)`; the other files do not. To be called before [asType] or [asContextualType]:
     * ```
     * register("custom").requiresOptIn("your.own.ExperimentalApi").asType("your.own.Type", "your.own.CustomKSerializer")
     * ```
     * Not needed for `kotlin.uuid.Uuid`: the files using it always opt in to `kotlin.uuid.ExperimentalUuidApi`, so they also compile with a
     * Kotlin API version older than 2.4, where it is experimental.
     *
     * @param markerClassNames the fully qualified names of the opt-in marker classes
     */
    public fun requiresOptIn(vararg markerClassNames: String): LogicalTypeBuilder {
        requiredOptIns = requiredOptIns + markerClassNames
        return this
    }

    /**
     * Maps the logical type to a given class with a serializer set at compile-time for better performances (no lookup at runtime).
     * The fields generated from a logical type will be annotated with `@kotlinx.serialization.Serializable(with = serializerTypeName)`.
     *
     * To specify the serializer at runtime, use [asContextualType] instead (for more advanced users).
     *
     * @param serializerTypeName the fully qualified name of the serializer to be used
     */
    public fun asType(className: String, serializerTypeName: String) {
        whenBuilt(logicalTypeName, LogicalTypeMapping(className, serializerTypeName, requiredOptIns))
    }

    /**
     * Creates the logical type without compile-time serializer.
     * The serializer have to be passed at runtime, as the generated fields from a logical type will be annotated with `@kotlinx.serialization.Contextual`:
     *
     * ```
     * Avro {
     *     serializersModule = SerializersModule {
     *         contextual(YourLogicalTypeClassSerializer())
     *     }
     * }
     * ```
     *
     * It can enable different serializers depending on the context (one for json, one for avro, ...).
     *
     * To specify the serializer at compile-time (recommended), use [asType] instead.
     */
    public fun asContextualType(className: String) {
        whenBuilt(logicalTypeName, LogicalTypeMapping(className, null, requiredOptIns))
    }
}

@CacheableTask
public abstract class GenerateKotlinAvroSourcesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    public abstract val inputFiles: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    @get:Input
    public abstract val logicalTypes: MapProperty<String, LogicalTypeMapping>

    @get:Input
    public abstract val useKotlinConventionForFieldNames: Property<Boolean>

    @get:Input
    public abstract val fileOptIns: SetProperty<String>

    @get:Input
    public abstract val fileAnnotations: ListProperty<FileAnnotation>

    @TaskAction
    public fun generateKotlinSources() {
        val logicalTypes = logicalTypes.get()
        val fieldNamingStrategy =
            if (useKotlinConventionForFieldNames.getOrElse(false)) {
                FieldNamingStrategy.CamelCase
            } else {
                FieldNamingStrategy.Identity
            }

        val kotlinGenerator =
            KotlinGenerator(
                fieldNamingStrategy = fieldNamingStrategy,
                additionalLogicalTypes =
                    logicalTypes.map {
                        KotlinGenerator.LogicalTypeDescriptor(
                            logicalTypeName = it.key,
                            kotlinClassName = it.value.type,
                            kSerializerClassName = it.value.serializerType,
                            requiredOptIns = it.value.requiredOptIns
                        )
                    },
                fileOptIns = fileOptIns.getOrElse(emptySet()).toList(),
                fileAnnotations =
                    fileAnnotations.getOrElse(emptyList()).map { annotation ->
                        AnnotationSpec.builder(ClassName.bestGuess(annotation.className))
                            .apply { annotation.arguments.forEach { addMember("%L", it) } }
                            .build()
                    }
            )
        val outputDir = outputDir.asFile.get()
        val files = getInputAvroSchemaFiles()

        if (files.isEmpty()) {
            val msg = "No files to generate as there are no input schema files. Please configure avro4k.inputFiles accordingly."
            logger.error(msg)
            error(msg)
        }

        kotlinGenerator.generateKotlinClassesFromFiles(files)
            .associate { (file, generatedFiles) ->
                logger.lifecycle("Schema $file generated ${generatedFiles.size} class(es):\n " + generatedFiles.joinToString("\n ") { it.fullName() })
                file to generatedFiles
            }
            .also { it.verifyNoNameClash() }
            .values.flatten().forEach { generatedFile ->
                generatedFile
                    .toBuilder()
                    .indent("    ")
                    .build()
                    .writeTo(outputDir)
            }
    }

    private fun getInputAvroSchemaFiles(): List<File> =
        inputFiles
            .asFileTree
            .matching { include("**/*.avsc") }
            .files
            .sorted()

    private fun FileSpec.fullName() = if (packageName.isEmpty()) name else "$packageName.$name"

    private fun Map<File, List<FileSpec>>.verifyNoNameClash() {
        // links generated class names to the schema files that generated them. If the set has more than one entry, we have a duplicate
        val names = mutableMapOf<String, MutableMap<File, FileSpec>>()
        forEach { (file, generatedFiles) ->
            generatedFiles.forEach { generatedFile ->
                names.getOrPut(generatedFile.fullName()) { mutableMapOf() }[file] = generatedFile
            }
        }

        // Keep only duplicates. Allow generating identical classes from different schema files
        names.entries.removeIf { it.value.size < 2 || it.value.values.toSet().size < 2 }

        if (names.isNotEmpty()) {
            val message =
                "Duplicate generated class names found with different schema files. " +
                    "This usually indicates that the same full-name is used across multiple avro schema files but with different content. " +
                    "Please check your input schema files:\n${
                        names.entries.joinToString("\n  ") { (generatedFullName, originalSchemaLocation) ->
                            "$generatedFullName has been generated differently from the following schemas: ${
                                originalSchemaLocation.keys.joinToString(", ") { it.path }
                            }"
                        }
                    }."
            logger.error(message)
            error(message)
        }
    }
}