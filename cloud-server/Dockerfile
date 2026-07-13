FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY cloud-common/pom.xml cloud-common/pom.xml
COPY cloud-pojo/pom.xml cloud-pojo/pom.xml
COPY cloud-server/pom.xml cloud-server/pom.xml
RUN mvn -pl cloud-server -am dependency:go-offline -DskipTests

COPY cloud-common/src cloud-common/src
COPY cloud-pojo/src cloud-pojo/src
COPY cloud-server/src cloud-server/src
RUN mvn -pl cloud-server -am package -DskipTests

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/cloud-server/target/cloud-server-1.0-SNAPSHOT.jar /app/cloud-server.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/cloud-server.jar"]
