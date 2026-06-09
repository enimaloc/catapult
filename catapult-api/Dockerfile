FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY catapult-api/build.gradle.kts ./catapult-api/
RUN ./gradlew :catapult-api:dependencies --no-daemon -q

COPY catapult-api/src ./catapult-api/src
COPY .git ./.git
RUN ./gradlew :catapult-api:build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S catapult && adduser -S catapult -G catapult

COPY --from=builder /app/catapult-api/build/libs/*.jar app.jar

RUN chown catapult:catapult app.jar

USER catapult

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
