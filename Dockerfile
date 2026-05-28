FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q

COPY src ./src
RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S catapult && adduser -S catapult -G catapult

COPY --from=builder /app/build/libs/*.jar app.jar

RUN chown catapult:catapult app.jar

USER catapult

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
