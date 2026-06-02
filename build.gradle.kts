plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.3.0.8198"
}

group = "fr.enimaloc"
version = "0.0.1-SNAPSHOT"
description = "catapult"

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

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

val igdbApiVersion = "1.3.2"
val unleashVersion = "9.2.4"
val growthbookVersion = "0.10.10"
val icuVersion = "76.1"
val chartjsVersion = "4.4.9"
val htmxVersion = "2.0.4"
val jsoupVersion = "1.17.2"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.husnjak:igdb-api-jvm:$igdbApiVersion")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.getunleash:unleash-client-java:$unleashVersion")
    implementation("com.github.growthbook:growthbook-sdk-java:$growthbookVersion")
    implementation("com.ibm.icu:icu4j:$icuVersion")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.webjars.npm:chart.js:$chartjsVersion")
    implementation("org.webjars.npm:htmx.org:$htmxVersion")
    implementation("org.webjars:webjars-locator-lite")

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
    testImplementation("org.jsoup:jsoup:$jsoupVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
                .start().inputStream.bufferedReader().readLine() ?: "unknown"
        }.getOrDefault("unknown")

        val gitCommit = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .start().inputStream.bufferedReader().readLine() ?: "unknown"
        }.getOrDefault("unknown")

        val gitRemote = runCatching {
            ProcessBuilder("git", "remote", "get-url", "origin")
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
