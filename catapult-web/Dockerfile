FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY catapult-common/build.gradle.kts ./catapult-common/
COPY catapult-api/build.gradle.kts ./catapult-api/
COPY catapult-web/build.gradle.kts ./catapult-web/
RUN ./gradlew :catapult-web:dependencies --no-daemon -q

COPY catapult-common/src ./catapult-common/src
COPY catapult-web/src ./catapult-web/src
COPY .git ./.git
# bootJar alone: the image has no Node, and `build` would run `check`, hence the Vitest suite (jsTest),
# besides leaving a second (-plain) jar for the COPY below. The tests run in CI, not here.
RUN ./gradlew :catapult-web:bootJar --no-daemon

# Ubuntu rather than Alpine: the avatars' WebP encoder (webp-imageio) bundles libwebp built for glibc, which musl
# can't load. wget: the compose healthchecks.
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/* \
    && groupadd --system catapult && useradd --system --gid catapult --no-create-home catapult

COPY --from=builder /app/catapult-web/build/libs/*.jar app.jar

RUN chown catapult:catapult app.jar
# The last API spec obtained (ApiDocsSpec, catapult.api-docs.cache-file), kept for when catapult-api is down.
RUN mkdir -p /app/data && chown catapult:catapult /app/data

USER catapult

# 8081: the site, 8092: the management port (actuator, Prometheus, the compose healthcheck)
EXPOSE 8081 8092

# webp-imageio loads libwebp (System.load), which the JDK warns of, and will refuse, unless allowed.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
