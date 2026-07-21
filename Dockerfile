FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl g++ \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --user-group --create-home --home-dir /home/dataforge dataforge \
    && mkdir -p /app /var/lib/dataforge/runtime \
    && chown -R dataforge:dataforge /app /var/lib/dataforge /home/dataforge

WORKDIR /app
COPY --from=build --chown=dataforge:dataforge /workspace/target/data-forge-*.jar /app/dataforge.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/dataforge.jar"]
