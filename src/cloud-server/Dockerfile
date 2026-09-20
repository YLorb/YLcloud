FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src/cloud-common/pom.xml src/cloud-common/pom.xml
COPY src/cloud-pojo/pom.xml src/cloud-pojo/pom.xml
COPY src/cloud-server/pom.xml src/cloud-server/pom.xml

COPY src/cloud-common/src src/cloud-common/src
COPY src/cloud-pojo/src src/cloud-pojo/src
COPY src/cloud-server/src src/cloud-server/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl :cloud-server -am package -DskipTests -Dmaven.wagon.http.retryHandler.count=5

FROM eclipse-temurin:17-jre

RUN if [ -f /etc/apt/sources.list.d/debian.sources ]; then sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources; fi \
    && if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources; fi \
    && if [ -f /etc/apt/sources.list ]; then sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list; fi \
    && apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends curl libreoffice-writer libreoffice-calc libreoffice-impress fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/src/cloud-server/target/cloud-server-1.0-SNAPSHOT.jar /app/cloud-server.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/cloud-server.jar"]
