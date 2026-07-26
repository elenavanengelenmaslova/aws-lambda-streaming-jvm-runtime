import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish") version "0.31.0"
    id("org.jetbrains.kotlinx.kover")
}

group = "nl.vintik"
version = "1.0.0"

dependencies {
    // --- AWS Lambda runtime contracts ---
    implementation("com.amazonaws:aws-lambda-java-core:${rootProject.extra["awsLambdaCoreVersion"]}")

    // --- Serialization ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["kotlinxSerializationVersion"]}")

    // --- Logging ---
    implementation("io.github.oshai:kotlin-logging-jvm:${rootProject.extra["kotlinLoggingVersion"]}")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")

    // --- Testing ---
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.extra["junitVersion"]}")
    testImplementation("io.mockk:mockk:${rootProject.extra["mockkVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Toolchain & compilation: Java 21 (LTS, Lambda-standard) --------------------------------
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
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
        description = "JVM implementation of the AWS Lambda / API Gateway streaming response protocol (metadata JSON + 8-byte delimiter + body). ResponseWriter encodes the wire format; copy() streams large payloads with bounded memory. No AWS SDK dependency."
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
