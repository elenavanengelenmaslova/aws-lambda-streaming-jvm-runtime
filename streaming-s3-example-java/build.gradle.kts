plugins {
    java
    id("com.gradleup.shadow")
    // Coverage: JaCoCo, NOT Kover. Kover (org.jetbrains.kotlinx.kover) only registers its JVM
    // coverage variant for modules where a Kotlin plugin is applied; on this pure-Java module
    // (only the `java` plugin) it never instruments the `test` task, so `koverVerify` passes
    // vacuously against an empty report ("No coverage information was found"). See docs/log.md
    // ("Kover on a pure-Java module ..."). JaCoCo instruments the compiled bytecode directly and
    // enforces the same 80% gate. The `koverVerify`/`koverHtmlReport` task names are preserved as
    // aliases below so the repo-wide command surface and CI keep working unchanged (Req 13.3, 15.2).
    jacoco
}

dependencies {
    // --- The library under test: consumed from Java to prove cross-language interop ---
    implementation(project(":streaming-core"))

    // --- AWS Lambda runtime contracts (RequestStreamHandler, Context) ---
    implementation("com.amazonaws:aws-lambda-java-core:${rootProject.extra["awsLambdaCoreVersion"]}")

    // --- AWS SDK for Java v2 (synchronous S3), NOT the coroutine-based Kotlin SDK ---
    implementation(platform("software.amazon.awssdk:bom:${rootProject.extra["awsSdkJavaVersion"]}"))
    implementation("software.amazon.awssdk:s3")

    // --- JSON: Jackson parses the API Gateway proxy event (idiomatic Java JSON reader) ---
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonVersion"]}")

    // --- kotlinx-serialization-json: required on the compile classpath purely to reference the
    // library's `ResponseWriter(json, maxPreludeLen)` constructor from Java.
    // :streaming-core exposes kotlinx-serialization only as `implementation` (runtime-only), so a
    // Java consumer must re-declare it to name `kotlinx.serialization.json.Json` (e.g. Json.Default)
    // at compile time. The Java module never serializes with it directly — the library owns that.
    // Interop friction logged for Req 1.5/14 (see docs/log.md via task 3.2). ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["kotlinxSerializationVersion"]}")

    // --- Logging ---
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- CRaC priming hook for SnapStart ---
    implementation("org.crac:crac:${rootProject.extra["cracVersion"]}")

    // --- Testing: JUnit Jupiter + Mockito (Java standard), TestContainers + LocalStack ---
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.extra["junitVersion"]}")
    testImplementation("org.mockito:mockito-core:${rootProject.extra["mockitoVersion"]}")
    testImplementation("org.mockito:mockito-junit-jupiter:${rootProject.extra["mockitoVersion"]}")
    testImplementation("org.testcontainers:testcontainers:${rootProject.extra["testcontainersVersion"]}")
    testImplementation("org.testcontainers:junit-jupiter:${rootProject.extra["testcontainersVersion"]}")
    testImplementation("org.testcontainers:localstack:${rootProject.extra["testcontainersVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Toolchain & compilation: Java 25 -------------------------------------------------------
// The example targets Java 25 while consuming the Java-21-compiled :streaming-core library.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.test {
    useJUnitPlatform {
        val excludeTags = project.findProperty("excludeTags") as? String
        if (!excludeTags.isNullOrBlank()) {
            excludeTags(excludeTags)
        }
    }
    // Mockito/Byte Buddy on Java 25: NOT needed. Mockito 5.23.0 bundles a Byte Buddy that already
    // recognises the JDK 25 class-file version, so all Mockito-based tests pass without the
    // `net.bytebuddy.experimental=true` opt-in the Kotlin/MockK module still requires. Confirmed at
    // the task-14.1 checkpoint (167 unit+property tests green with the flag absent). See docs/log.md.
}

// ---- Fat jar for Lambda deployment (Shadow) -------------------------------------------------
// Distinct name from the Kotlin example's streaming-endpoint.jar so both build into build/dist
// without overwriting each other.
tasks.shadowJar {
    archiveFileName.set("streaming-endpoint-java.jar")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// ---- Coverage gate: 80% line coverage via JaCoCo --------------------------------------------
// JaCoCo 0.8.14 is the first release to officially support Java 25 class files (0.8.13 was
// experimental); pin it so the Java 25 toolchain's bytecode instruments cleanly.
jacoco {
    toolVersion = "0.8.14"
}

// The report/verification tasks read the execution data produced by the `test` task (the JaCoCo
// plugin attaches its agent to every Test task automatically). Wiring `dependsOn(test)` makes the
// report/verify run the tests first, matching Kover's "running a report triggers the tests" UX and
// honouring `-PexcludeTags=integration` (the exclusion is configured on `tasks.test`).
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// ---- Compatibility aliases: keep the repo-wide `kover*` command surface working --------------
// The task-14.1 command and CI (workflow-build.yml) invoke `:streaming-s3-example-java:koverVerify`
// (and `koverHtmlReport`). Since this module runs on JaCoCo (see the plugins note above), expose
// those names as thin aliases delegating to the JaCoCo tasks so nothing downstream has to change.
tasks.register("koverHtmlReport") {
    group = "verification"
    description = "Alias for jacocoTestReport (HTML coverage). Kept for repo-wide command/CI compatibility; this pure-Java module uses JaCoCo, not Kover."
    dependsOn(tasks.jacocoTestReport)
}

tasks.register("koverVerify") {
    group = "verification"
    description = "Alias for jacocoTestCoverageVerification (80% line gate). Kept for repo-wide command/CI compatibility; this pure-Java module uses JaCoCo, not Kover."
    dependsOn(tasks.jacocoTestCoverageVerification)
}
