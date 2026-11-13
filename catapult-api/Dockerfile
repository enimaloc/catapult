FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY catapult-common/build.gradle.kts ./catapult-common/
COPY catapult-bgs/build.gradle.kts ./catapult-bgs/
COPY catapult-api/build.gradle.kts ./catapult-api/
COPY catapult-web/build.gradle.kts ./catapult-web/
RUN ./gradlew :catapult-api:dependencies --no-daemon -q

COPY catapult-common/src ./catapult-common/src
COPY catapult-bgs/src ./catapult-bgs/src
COPY catapult-api/src ./catapult-api/src
COPY CHANGELOG.md ./
COPY .git ./.git
# bootJar alone: `build` would also run the tests and leave a second (-plain) jar for the COPY below
RUN ./gradlew :catapult-api:bootJar --no-daemon

# glibc base: Chromium (Battle.net login, Playwright) does not run on musl
FROM eclipse-temurin:25-jre-noble

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl jq && rm -rf /var/lib/apt/lists/*
RUN groupadd --system catapult && useradd --system --gid catapult catapult

COPY --from=builder /app/catapult-api/build/libs/*.jar app.jar

# Chromium and its OS libraries are installed at build time (never at startup) with Playwright's CLI, run from
# the boot jar's classpath through PropertiesLauncher; the browsers live in a folder the catapult user can read
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN java -Dloader.main=com.microsoft.playwright.CLI -cp app.jar \
        org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/* \
    && chown -R catapult:catapult /ms-playwright app.jar

# Only Chromium is baked in: Playwright.create() must not try to download the other browsers at runtime
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

USER catapult

# 8081: catapult-api, reached by catapult-web only; 8091: the management port (actuator, Prometheus, the compose
# healthcheck)
EXPOSE 8081 8091

ENTRYPOINT ["java", "-jar", "app.jar"]
