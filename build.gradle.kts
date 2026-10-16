plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "io.github.joyen09.exchangecore"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.kafka:spring-kafka")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redpanda")
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // NoOverrideSwitchTest scans the working tree for isolation violations, so the working tree is
    // genuinely an input to this task. Without declaring it, Gradle reports an up-to-date pass
    // after someone edits a file the scanner would have rejected.
    inputs
        .files(fileTree(layout.projectDirectory) {
            exclude("build/**", ".gradle/**", ".git/**", ".idea/**", "**/*.jar")
        })
        .withPropertyName("repositoryTree")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }

    // Fail before a single test runs if Docker is not there.
    //
    // Nearly every test here needs Testcontainers, so a dead daemon turns the whole suite red at once
    // — about seventy failures, each with a DockerClientProviderStrategy stack trace and none about
    // the code. That happened five times while Phase 3 was being built, and the cost each time was the
    // minutes spent establishing that none of the failures was anyone's fault.
    //
    // A JUnit-level check was tried first and is kept for IDE runs (DockerPreflight), but it is not
    // enough on its own: Gradle prints only the exception's class name, so the actionable message was
    // invisible and a developer still got one identical line per test saying nothing. The gate belongs
    // here, where one message can be printed before anything starts.
    //
    // -PskipDockerCheck exists for running only the unit tests, which genuinely do not need a daemon.
    doFirst {
        if (!project.hasProperty("skipDockerCheck") && !dockerIsReachable()) {
            // Delete the previous run's report before failing.
            //
            // A gate that stops the build leaves whatever the last run wrote lying in build/, and a
            // report from an earlier run looks exactly like a report from this one — same path, same
            // green summary, no indication that nothing ran. That is a worse trap than the seventy
            // stack traces this gate replaced, because a stale green is not weak evidence about this
            // run, it is somebody else's evidence about a different one.
            //
            // It has already happened here once: the gate fired, the build failed, and
            // build/test-results still said 299 tests, 0 failures from the run before.
            //
            // The project's own rule applies — don't remember to check the timestamp, make the stale
            // report impossible to misread. Deleting it is the only form of that available, since
            // nothing can stop a reader opening a file that is there.
            //
            // One limit, found while probing this: when the test task is UP-TO-DATE, Gradle skips it
            // entirely and doFirst never runs, so a daemon-less invocation succeeds and leaves the
            // report alone. That is correct rather than a hole — the inputs have not changed, so the
            // previous result is still the answer for them — but it does mean the gate is not
            // unconditional, and somebody reading this should know which case it covers.
            val discarded =
                listOf(
                    reports.junitXml.outputLocation.get().asFile,
                    reports.html.outputLocation.get().asFile,
                ).count { it.exists() && it.deleteRecursively() }

            throw GradleException(
                "\n" +
                    "Docker is not reachable, so the integration tests cannot run.\n\n" +
                    "This is an environment problem, not a test failure. Without this check every\n" +
                    "test that needs PostgreSQL or Redpanda fails with the same cause and its own\n" +
                    "stack trace.\n\n" +
                    (if (discarded > 0) {
                        "The previous run's test report has been deleted. It would have shown that\n" +
                            "run's results under this run's path, and a stale green report is harder\n" +
                            "to catch than a red one.\n\n"
                    } else {
                        ""
                    }) +
                    "To fix it:\n" +
                    "    start the Docker daemon\n" +
                    "    docker info        # should print a server version\n" +
                    "    ./gradlew test     # re-run\n\n" +
                    "To run only the unit tests without a daemon:\n" +
                    "    ./gradlew test -PskipDockerCheck --tests '*SomeUnitTest*'\n\n" +
                    "See \"Local development only\" in the README's Known limitations.\n",
            )
        }
    }
}

/**
 * True when a Docker daemon answers.
 *
 * The criterion itself is in scripts/docker-available.sh, not here. It had been written out twice —
 * once in this file and once in DockerPreflight — and two statements of one rule drift: a timeout
 * changed on one side, or a version requirement added, and the IDE and the command line start
 * disagreeing about whether the tests can run. One file, three callers.
 */
fun dockerIsReachable(): Boolean =
    try {
        ProcessBuilder("sh", "scripts/docker-available.sh")
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    // Pinned so tests carrying Unicode escapes and non-ASCII fixtures behave identically on a
    // developer machine and on a CI runner with a different platform default.
    options.encoding = "UTF-8"
}
