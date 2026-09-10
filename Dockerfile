FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 platform && useradd --uid 10001 --gid platform --no-create-home platform \
    && mkdir -p /app /data/objects && chown -R platform:platform /app /data
WORKDIR /app
COPY --from=build --chown=platform:platform /build/target/distributed-digital-asset-platform-1.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENV STORAGE_ROOT=/data/objects
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Djava.awt.headless=true", "-jar", "/app/app.jar"]
