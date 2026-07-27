FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/app.jar ./app.jar

EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]
