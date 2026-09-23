tasks.register("actionsBeforeCommit") {
    group = "verification"
    val tasksToBeRun = listOf(
        "classes",
        "testClasses",
        // Kotlin Multiplatform modules have no `classes`/`testClasses`: compile their JVM target instead
        "jvmMainClasses",
        "jvmTestClasses",
        // ... and their common sources alone, so a JVM-only API used in commonMain fails here instead of on another target
        "compileCommonMainKotlinMetadata",
        "apiDump",
        "spotlessApply"
    )
    subprojects {
        tasksToBeRun.forEach { task ->
            tasks.findByName(task)?.let { dependsOn(it) }
        }
    }
}

repositories {
    // Need maven central here and not into a buildSrc module to allow IntelliJ downloading sources
    mavenCentral()
}
