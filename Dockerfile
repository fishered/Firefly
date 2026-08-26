FROM amazoncorretto:21-alpine AS build
WORKDIR /workspace

COPY . .
RUN chmod +x gradlew \
    && ./gradlew --no-daemon :server:launcher:installDist

FROM amazoncorretto:21-alpine

ARG FIREFLY_VERSION=1.1.0
LABEL org.opencontainers.image.title="Firefly Server" \
      org.opencontainers.image.description="Distributed scheduling server" \
      org.opencontainers.image.version="${FIREFLY_VERSION}"

RUN apk add --no-cache curl \
    && addgroup -S -g 10001 firefly \
    && adduser -S -D -H -u 10001 -G firefly -s /sbin/nologin firefly

WORKDIR /opt/firefly

COPY --from=build --chown=firefly:firefly /workspace/server/launcher/build/install/launcher/ ./
COPY --chown=firefly:firefly config ./config

RUN mkdir -p data plugins \
    && chown -R firefly:firefly /opt/firefly

ENV FIREFLY_CONFIG=/opt/firefly/config/firefly-server.properties \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 9700 9710 9711

USER firefly

ENTRYPOINT ["/opt/firefly/bin/launcher"]
