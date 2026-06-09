FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY catapult-web/build.gradle.kts ./catapult-web/
RUN ./gradlew :catapult-web:dependencies --no-daemon -q

COPY catapult-web/src ./catapult-web/src
COPY .git ./.git
RUN ./gradlew :catapult-web:build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S catapult && adduser -S catapult -G catapult

COPY --from=builder /app/catapult-web/build/libs/*.jar app.jar

RUN chown catapult:catapult app.jar

USER catapult

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
