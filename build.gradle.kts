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
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
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
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    // Pinned so tests carrying Unicode escapes and non-ASCII fixtures behave identically on a
    // developer machine and on a CI runner with a different platform default.
    options.encoding = "UTF-8"
}
