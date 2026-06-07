rootProject.name = "s3-file-streaming-endpoint"

// Single module: the example is deliberately flat (see structure.md). No sub-project includes.

plugins {
    // Resolves JDK toolchains (Java 25) from foojay so the build can provision the
    // toolchain automatically instead of requiring a pre-installed JDK 25.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
