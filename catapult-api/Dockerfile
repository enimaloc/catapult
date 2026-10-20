FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY catapult-common/build.gradle.kts ./catapult-common/
COPY catapult-api/build.gradle.kts ./catapult-api/
COPY catapult-web/build.gradle.kts ./catapult-web/
RUN ./gradlew :catapult-api:dependencies --no-daemon -q

COPY catapult-common/src ./catapult-common/src
COPY catapult-api/src ./catapult-api/src
COPY .git ./.git
# bootJar alone: `build` would also run the tests and leave a second (-plain) jar for the COPY below
RUN ./gradlew :catapult-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl jq
RUN addgroup -S catapult && adduser -S catapult -G catapult

COPY --from=builder /app/catapult-api/build/libs/*.jar app.jar

RUN chown catapult:catapult app.jar

USER catapult

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
