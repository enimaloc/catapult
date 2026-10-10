import java.time.Instant

plugins {
    java
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
}

description = "catapult-web"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation(project(":catapult-common"))

    implementation("org.mvnpm:mdui:2.1.4")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.webjars:webjars-locator-lite")
    implementation("io.micrometer:micrometer-registry-prometheus")

    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

springBoot {
    buildInfo {
        val gitBranch = runCatching {
            ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(rootProject.projectDir)
                .start().inputStream.bufferedReader().readLine() ?: "unknown"
        }.getOrDefault("unknown")

        val gitCommit = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(rootProject.projectDir)
                .start().inputStream.bufferedReader().readLine() ?: "unknown"
        }.getOrDefault("unknown")

        val gitRemote = runCatching {
            ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(rootProject.projectDir)
                .start().inputStream.bufferedReader().readLine() ?: ""
        }.getOrDefault("")

        val repositoryUrl = gitRemote
            .replace(Regex("^(?:ssh://)?git@([^:/]+)[:/](.+?)(\\.git)?$"), "https://$1/$2")
            .replace(Regex("\\.git$"), "")
            .ifBlank { "#" }

        val privacyDir = file("src/main/resources/lang/privacy")
        val privacyProperties = if (privacyDir.exists()) {
            privacyDir
                .listFiles { file -> file.isFile && file.extension == "html" }
                .orEmpty()
                .associate { file ->
                    val language = file.nameWithoutExtension
                    "privacy.$language.last-update" to Instant.ofEpochMilli(file.lastModified()).toString()
                }
        } else emptyMap()

        properties {
            additional.set(
                mapOf(
                    "git.branch" to gitBranch,
                    "git.commit" to gitCommit,
                    "git.repository-url" to repositoryUrl
                ) + privacyProperties
            )
        }
    }
}

/*
 * Browser-script unit tests (Vitest + jsdom, see vitest.config.js), part of `check`.
 */
val npmInstall = tasks.register<Exec>("npmInstall") {
    description = "Installs the JS test toolchain from package-lock.json."
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
    commandLine("npm", "ci", "--no-fund", "--no-audit")
}

val jsTest = tasks.register<Exec>("jsTest") {
    description = "Runs the browser-script unit tests with coverage."
    group = "verification"
    dependsOn(npmInstall)
    inputs.files("vitest.config.js", "package-lock.json")
    inputs.dir("src/main/resources/static/js")
    inputs.dir("src/test/js")
    outputs.dir(layout.buildDirectory.dir("reports/js-coverage"))
    commandLine("npx", "vitest", "run", "--coverage")
}

tasks.named("check") {
    dependsOn(jsTest)
}
