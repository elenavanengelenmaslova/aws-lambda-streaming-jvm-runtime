import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish") version "0.31.0"
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

group = "nl.vintik"

// The published version comes from -PreleaseVersion, which the publish workflow derives from the
// git tag. Keeping it out of this file means the tag is the single source of truth: a tag can no
// longer trigger a release that publishes some other, stale version number.
version = providers.gradleProperty("releaseVersion").getOrElse("2.0.0-SNAPSHOT")

dependencies {
    // --- Serialization ---
    // The only runtime dependency. No AWS artifacts: this module implements the wire protocol and
    // never touches the Lambda or S3 APIs, so consumers pick their own AWS dependencies.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["kotlinxSerializationVersion"]}")

    // --- Testing ---
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.extra["junitVersion"]}")
    testImplementation("io.mockk:mockk:${rootProject.extra["mockkVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Toolchain & compilation: Java 21 (LTS, Lambda-standard) --------------------------------
// Deliberately below the example module's Java 25: a library's bytecode target is a floor for
// every consumer, and java21 remains a supported Lambda runtime.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    // Every public declaration must state its visibility and return type — no accidental API.
    explicitApi()

    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
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

// ---- Maven Central publishing ---------------------------------------------------------------
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("nl.vintik", "aws-lambda-streaming-core", version.toString())

    pom {
        name = "AWS Lambda Streaming Core"
        description = "JVM implementation of the AWS Lambda / API Gateway streaming response protocol (metadata JSON + 8-byte delimiter + body). ResponseWriter encodes the wire format; copy() streams large payloads with bounded memory. Depends only on kotlinx-serialization — no AWS artifacts, no logging framework."
        url = "https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        developers {
            developer {
                id = "elenavanengelenmaslova"
                name = "Elena van Engelen-Maslova"
                url = "https://github.com/elenavanengelenmaslova"
            }
        }
        scm {
            url = "https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime"
            connection = "scm:git:git://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime.git"
            developerConnection = "scm:git:ssh://git@github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime.git"
        }
    }
}
