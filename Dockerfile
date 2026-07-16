# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY . .
RUN ./gradlew --no-daemon :server:launcher:installDist

FROM eclipse-temurin:21-jre
WORKDIR /opt/firefly

COPY --from=build /workspace/server/launcher/build/install/launcher/ ./
COPY config ./config

ENV FIREFLY_CONFIG=/opt/firefly/config/firefly-server.properties

EXPOSE 9700 9710 9711

ENTRYPOINT ["./bin/launcher"]
