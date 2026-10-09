FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN sh ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends scribus xvfb xauth fonts-liberation \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/data /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix && chown app:app /app/data
COPY --from=build --chown=app:app /workspace/build/libs/cardSearch.jar ./app.jar
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh && chmod +x /usr/local/bin/entrypoint.sh

USER app
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
