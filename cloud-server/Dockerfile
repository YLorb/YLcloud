FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY cloud-common/pom.xml cloud-common/pom.xml
COPY cloud-pojo/pom.xml cloud-pojo/pom.xml
COPY cloud-server/pom.xml cloud-server/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl cloud-server -am dependency:go-offline -DskipTests -Dmaven.wagon.http.retryHandler.count=5

COPY cloud-common/src cloud-common/src
COPY cloud-pojo/src cloud-pojo/src
COPY cloud-server/src cloud-server/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl cloud-server -am package -DskipTests -Dmaven.wagon.http.retryHandler.count=5

FROM eclipse-temurin:17-jre

RUN if [ -f /etc/apt/sources.list.d/debian.sources ]; then sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources; fi \
    && if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources; fi \
    && if [ -f /etc/apt/sources.list ]; then sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list; fi \
    && apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/cloud-server/target/cloud-server-1.0-SNAPSHOT.jar /app/cloud-server.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/cloud-server.jar"]
