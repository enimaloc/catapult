FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY gradlew settings.gradle.kts build.gradle.kts ./
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

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S catapult && adduser -S catapult -G catapult

COPY --from=builder /app/catapult-web/build/libs/*.jar app.jar

RUN chown catapult:catapult app.jar

USER catapult

# 8081: the site, 8092: the management port (actuator, Prometheus, the compose healthcheck)
EXPOSE 8081 8092

ENTRYPOINT ["java", "-jar", "app.jar"]
