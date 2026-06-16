import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// build.gradle.kts is the source of truth for versions (see tech.md). Bump together with MockNest.

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.gradleup.shadow") version "9.0.2"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "com.example.streaming"
version = "0.1.0"

// ---- Dependency versions (single source of truth) -------------------------------------------
val awsLambdaCoreVersion = "1.4.0"
val awsLambdaEventsVersion = "3.16.1"
val awsSdkKotlinVersion = "1.6.59"
val kotlinxSerializationVersion = "1.9.0"
val kotlinLoggingVersion = "7.0.7"
val cracVersion = "1.5.0"

val junitVersion = "6.0.0"
val mockkVersion = "1.14.5"
val coroutinesVersion = "1.10.2"
val testcontainersVersion = "1.21.4"

dependencies {
    // --- AWS Lambda runtime contracts ---
    implementation("com.amazonaws:aws-lambda-java-core:$awsLambdaCoreVersion")
    implementation("com.amazonaws:aws-lambda-java-events:$awsLambdaEventsVersion")

    // --- AWS SDK for Kotlin (NOT the Java SDK) ---
    implementation("aws.sdk.kotlin:s3:$awsSdkKotlinVersion")

    // --- Serialization (preferred over Jackson) ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // --- Logging ---
    implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    // SLF4J simple provider so kotlin-logging outputs to CloudWatch (not NOP)
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- CRaC priming hook for SnapStart ---
    implementation("org.crac:crac:$cracVersion")

    // --- Testing ---
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:localstack:$testcontainersVersion")

    // JUnit Platform launcher on the test runtime classpath (required by JUnit 6 / Gradle).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Toolchain & compilation: Kotlin 2.3.x targeting Java 25 --------------------------------
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.test {
    useJUnitPlatform {
        val excludeTags = project.findProperty("excludeTags") as? String
        if (!excludeTags.isNullOrBlank()) {
            excludeTags(excludeTags)
        }
    }
    // MockK mocks final Kotlin classes via byte-buddy class redefinition. On the Java 25
    // toolchain the bundled byte-buddy must be told to accept the newer class-file version,
    // otherwise instrumentation is silently skipped and mocked calls are never intercepted.
    systemProperty("net.bytebuddy.experimental", "true")
}

// ---- Fat jar for Lambda deployment (Shadow) -------------------------------------------------
tasks.shadowJar {
    archiveFileName.set("streaming-endpoint.jar")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    // Merge SDK/service metadata so AWS SDK service loaders resolve inside the fat jar.
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// ---- Coverage gate: 90% via koverVerify (see tech.md) ---------------------------------------
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 90
                }
            }
        }
    }
}
