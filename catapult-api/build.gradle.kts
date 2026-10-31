import java.time.Instant

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.sonarqube") version "7.5.0.8588"
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
val unleashVersion = "9.3.2"
val growthbookVersion = "0.11.3"
val icuVersion = "76.1"
val jsoupVersion = "1.23.2"
val jjwtVersion = "0.13.0"
val commonmarkVersion = "0.31.0"
val springCloudContextVersion = "5.0.4"

dependencies {
    implementation(project(":catapult-common"))

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
    // Sentry, only for the members in debug mode (see DebugSentryConfig)
    implementation(platform("io.sentry:sentry-bom:8.60.0"))
    implementation("io.sentry:sentry-spring-boot-4-starter")
    implementation("io.sentry:sentry-logback")
    implementation("org.jsoup:jsoup:$jsoupVersion")
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.springframework.cloud:spring-cloud-context:$springCloudContextVersion")

    // GraalJS sandbox for executing compiled chat-command JS
    implementation("org.graalvm.polyglot:polyglot:24.2.2")
    implementation("org.graalvm.polyglot:js:24.2.2")

    // JWT generation/validation
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("net.ttddyy:datasource-proxy:1.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generateChangelog = tasks.register("generateChangelog") {
    val outputFile = file("src/main/resources/changelog.log")
    // Merge commit -> pull request title, written by the release workflow; absent locally, where the subjects are used
    val prTitlesFile = file("src/main/pr-titles.tsv")
    outputs.file(outputFile)
    outputs.upToDateWhen { false }
    doLast {
        fun git(vararg args: String) = ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .start().inputStream.bufferedReader().readLines()
        val prTitles = if (prTitlesFile.exists()) {
            prTitlesFile.readLines().map { it.split("\t", limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
        } else emptyMap()
        // What a range brings: its merged pull requests, or its commits for a version made without any
        fun entries(range: String): List<String> {
            fun log(kind: String) = git("log", kind, "--format=%H|%h|%s", range).mapNotNull { line ->
                val (full, short, subject) = line.split("|", limit = 3)
                val title = (prTitles[full] ?: subject).replace("|", "/")
                if (title.startsWith("chore(release)")) null else "$short|$title|"
            }
            return log("--merges").ifEmpty { log("--no-merges") }
        }
        val lines = runCatching {
            val tags = git("-c", "versionsort.suffix=-BETA", "tag", "--list", "v*", "--sort=-v:refname")
            val unreleased = tags.firstOrNull()?.let { entries("$it..HEAD") } ?: entries("HEAD")
            unreleased + tags.flatMapIndexed { i, tag ->
                val previous = tags.getOrNull(i + 1)
                listOf("||tag: $tag") + entries(if (previous != null) "$previous..$tag" else tag)
            }
        }.getOrDefault(emptyList())
        // Rewritten only when it changes: a touched resource makes devtools restart the running app
        val content = lines.joinToString("\n")
        if (!outputFile.exists() || outputFile.readText() != content) {
            outputFile.writeText(content)
        }
    }
}

tasks.processResources {
    dependsOn(generateChangelog)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.test {
    // EnglishConventionsTest has its own task: it reads the sibling modules' sources
    filter.excludeTestsMatching("*EnglishConventionsTest")
}

// EnglishConventionsTest reads the sibling modules' sources: only this task reruns when they change
val englishConventions = tasks.register<Test>("englishConventions") {
    description = "Checks that the code, comments, logs and test names of every module are written in English."
    group = "verification"
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    filter.includeTestsMatching("*EnglishConventionsTest")
    listOf("catapult-common", "catapult-web").forEach { module ->
        inputs.dir(rootProject.file("$module/src"))
            .withPropertyName("${module}Sources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

tasks.check {
    dependsOn(englishConventions)
}

springBoot {
    buildInfo {
        // No build time: it would make every build rewrite build-info.properties, and devtools restart the app
        excludes.set(setOf("time"))

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
