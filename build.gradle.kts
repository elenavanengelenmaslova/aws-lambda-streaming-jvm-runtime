// Root build.gradle.kts — version catalog only. All build logic lives in subproject build files.
// Versions are the single source of truth; bump here and subprojects pick them up via rootProject.extra.

plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
    id("com.gradleup.shadow") version "9.0.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}

// ---- Dependency versions (single source of truth) -------------------------------------------
extra["awsLambdaCoreVersion"] = "1.4.0"
extra["awsLambdaEventsVersion"] = "3.16.1"
extra["awsSdkKotlinVersion"] = "1.6.59"
extra["kotlinxSerializationVersion"] = "1.9.0"
extra["kotlinLoggingVersion"] = "7.0.7"
extra["cracVersion"] = "1.5.0"

extra["junitVersion"] = "6.0.0"
extra["mockkVersion"] = "1.14.5"
extra["coroutinesVersion"] = "1.10.2"
extra["testcontainersVersion"] = "1.21.4"
