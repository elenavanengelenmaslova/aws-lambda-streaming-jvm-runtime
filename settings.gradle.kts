rootProject.name = "aws-lambda-streaming-jvm-runtime"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("streaming-core")
include("streaming-s3-example")
include("streaming-s3-example-java")
