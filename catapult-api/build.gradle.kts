import java.time.Instant

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.3.0.8198"
}

description = "catapult-api"

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

val igdbApiVersion = "1.3.2"
val unleashVersion = "9.2.4"
val growthbookVersion = "0.10.10"
val icuVersion = "76.1"
val jsoupVersion = "1.17.2"
val jjwtVersion = "0.12.6"
val commonmarkVersion = "0.22.0"
// NOTE: Spring Boot 4.0.4 + Spring Cloud GA mismatch — using snapshot until 5.0 GA.
val springCloudContextVersion = "5.0.3-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Exposes RestClientCustomizer + the auto-configured RestClient.Builder bean
    // used by WebClientConfig to install the trace-logging interceptor.
    implementation("org.springframework.boot:spring-boot-restclient")
    // Thymeleaf kept for the experiment dialect (custom th:* processors and tests)
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.husnjak:igdb-api-jvm:$igdbApiVersion")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("io.getunleash:unleash-client-java:$unleashVersion")
    implementation("com.github.growthbook:growthbook-sdk-java:$growthbookVersion")
    implementation("com.ibm.icu:icu4j:$icuVersion")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.jsoup:jsoup:$jsoupVersion")
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.springframework.cloud:spring-cloud-context:$springCloudContextVersion")

    // JWT generation/validation
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generateChangelog = tasks.register("generateChangelog") {
    val outputFile = file("src/main/resources/changelog.log")
    outputs.file(outputFile)
    doLast {
        val lines = runCatching {
            ProcessBuilder("git", "log", "--format=%h|%s|%D", "--no-merges", "-n", "100")
                .directory(rootProject.projectDir)
                .start().inputStream.bufferedReader().readLines()
        }.getOrDefault(emptyList())
        outputFile.writeText(lines.joinToString("\n"))
    }
}

tasks.processResources {
    dependsOn(generateChangelog)
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required = true
        html.required = true
    }
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

        properties {
            additional.set(
                mapOf(
                    "git.branch" to gitBranch,
                    "git.commit" to gitCommit,
                    "git.repository-url" to repositoryUrl
                )
            )
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "enimaloc_catapult_876520ec-6736-4f21-ae81-d9ee3ec3c1ce")
        property("sonar.projectName", "catapult")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
    }
}
