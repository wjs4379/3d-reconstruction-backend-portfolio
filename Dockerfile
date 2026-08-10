# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src ./src

ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx768m"
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew clean bootJar --no-daemon -Pkotlin.compiler.execution.strategy=in-process && \
    jar_path="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" && \
    test -n "$jar_path" && \
    cp "$jar_path" /workspace/app.jar

FROM eclipse-temurin:21-jre-noble AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system app && \
    useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
