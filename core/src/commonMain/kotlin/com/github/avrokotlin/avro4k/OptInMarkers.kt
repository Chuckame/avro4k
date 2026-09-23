package com.github.avrokotlin.avro4k

/**
 * Public API marked with this annotation is effectively **internal**, which means
 * it should not be used outside of `com.github.avrokotlin.avro4k`.
 * Signature, semantics, source and binary compatibilities are not guaranteed for this API
 * and will be changed without any warnings or migration aids.
 * If you cannot avoid using internal API to solve your problem, please report your use-case to avro4k's issue tracker.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.TYPEALIAS)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
public annotation class InternalAvro4kApi

/**
 * This annotation marks an avro4k API as experimental and subject to issues.
 * However, the API should be tested enough, and should not change in the future, except if a bug or an abnormal behavior is found.
 * In case of issue, please report your use-case to avro4k's issue tracker.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.TYPEALIAS)
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
public annotation class ExperimentalAvro4kApi