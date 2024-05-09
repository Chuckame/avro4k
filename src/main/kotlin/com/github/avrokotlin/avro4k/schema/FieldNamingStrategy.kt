package com.github.avrokotlin.avro4k.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor

public interface FieldNamingStrategy {
    public fun resolve(
        descriptor: SerialDescriptor,
        elementIndex: Int,
    ): String

    public companion object Builtins {
        /**
         * Returns the field name as is.
         */
        @ExperimentalSerializationApi
        public object NoOp : FieldNamingStrategy {
            override fun resolve(
                descriptor: SerialDescriptor,
                elementIndex: Int,
            ): String = descriptor.getElementName(elementIndex)
        }

        /**
         * Convert the field name to snake_case by adding an underscore before each capital letter, and lowercase those capital letters.
         */
        public object SnakeCase : FieldNamingStrategy {
            override fun resolve(
                descriptor: SerialDescriptor,
                elementIndex: Int,
            ): String =
                descriptor.getElementName(elementIndex).let { serialName ->
                    buildString(serialName.length * 2) {
                        var bufferedChar: Char? = null
                        var previousUpperCharsCount = 0

                        serialName.forEach { c ->
                            if (c.isUpperCase()) {
                                if (previousUpperCharsCount == 0 && isNotEmpty() && last() != '_') {
                                    append('_')
                                }

                                bufferedChar?.let(::append)

                                previousUpperCharsCount++
                                bufferedChar = c.lowercaseChar()
                            } else {
                                if (bufferedChar != null) {
                                    if (previousUpperCharsCount > 1 && c.isLetter()) {
                                        append('_')
                                    }
                                    append(bufferedChar)
                                    previousUpperCharsCount = 0
                                    bufferedChar = null
                                }
                                append(c)
                            }
                        }

                        if (bufferedChar != null) {
                            append(bufferedChar)
                        }
                    }
                }
        }

        /**
         * Enforce camelCase naming strategy by upper-casing the first field name letter.
         */
        @ExperimentalSerializationApi
        public object PascalCase : FieldNamingStrategy {
            override fun resolve(
                descriptor: SerialDescriptor,
                elementIndex: Int,
            ): String = descriptor.getElementName(elementIndex).replaceFirstChar { it.uppercaseChar() }
        }
    }
}