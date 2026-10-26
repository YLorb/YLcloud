FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/cloud-gateway-1.0-SNAPSHOT.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
