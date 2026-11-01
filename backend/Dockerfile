# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
# libarchive-tools fornece o bsdtar, usado para ler arquivos CBR
RUN apt-get update \
    && apt-get install -y --no-install-recommends libarchive-tools curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /library /extra /data && chmod 777 /library /extra /data
WORKDIR /app
COPY --from=build /src/target/mangaverse-backend-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70" \
    HOME=/tmp
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
