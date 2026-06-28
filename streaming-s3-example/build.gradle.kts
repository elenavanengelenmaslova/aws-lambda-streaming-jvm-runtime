import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    implementation(project(":streaming-core"))

    // --- AWS Lambda runtime contracts ---
    implementation("com.amazonaws:aws-lambda-java-core:${rootProject.extra["awsLambdaCoreVersion"]}")

    // --- AWS SDK for Kotlin (NOT the Java SDK) ---
    implementation("aws.sdk.kotlin:s3:${rootProject.extra["awsSdkKotlinVersion"]}")

    // --- Logging ---
    implementation("io.github.oshai:kotlin-logging-jvm:${rootProject.extra["kotlinLoggingVersion"]}")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- Serialization (RequestParser uses kotlinx-serialization in main source) ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["kotlinxSerializationVersion"]}")

    // --- CRaC priming hook for SnapStart ---
    implementation("org.crac:crac:${rootProject.extra["cracVersion"]}")

    // --- Testing ---
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.extra["junitVersion"]}")
    testImplementation("io.mockk:mockk:${rootProject.extra["mockkVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    testImplementation("org.testcontainers:testcontainers:${rootProject.extra["testcontainersVersion"]}")
    testImplementation("org.testcontainers:junit-jupiter:${rootProject.extra["testcontainersVersion"]}")
    testImplementation("org.testcontainers:localstack:${rootProject.extra["testcontainersVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Toolchain & compilation: Java 25 -------------------------------------------------------
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
    systemProperty("net.bytebuddy.experimental", "true")
}

// ---- Fat jar for Lambda deployment (Shadow) -------------------------------------------------
tasks.shadowJar {
    archiveFileName.set("streaming-endpoint.jar")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// ---- Coverage gate: 90% via koverVerify -----------------------------------------------------
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
