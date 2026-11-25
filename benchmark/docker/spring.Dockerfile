# syntax=docker/dockerfile:1
# The official TechEmpower Spring Boot implementation (frameworks/Java/spring), at the final
# commit of the TechEmpower repo, which was archived in March 2026.

FROM maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
ARG TFB_COMMIT=57d92fbec6f8fd7431bc77326dd0484e60c96e20
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends git > /dev/null && rm -rf /var/lib/apt/lists/*
WORKDIR /tfb
# Shallow, partial, sparse clone: only this one framework's files are downloaded. The repo is
# archived, so its tip is TFB_COMMIT; the test below fails loudly if that ever stops being true.
RUN git clone -q --depth 1 --filter=blob:none --no-checkout https://github.com/TechEmpower/FrameworkBenchmarks.git . && \
    git sparse-checkout set frameworks/Java/spring && \
    git checkout -q "$TFB_COMMIT" && \
    test "$(git rev-parse HEAD)" = "$TFB_COMMIT"
WORKDIR /tfb/frameworks/Java/spring
RUN --mount=type=cache,target=/root/.m2 mvn -q package -DskipTests && \
    echo "$TFB_COMMIT" > /tfb/commit

FROM eclipse-temurin:25-jdk
WORKDIR /spring
COPY --from=build /tfb/frameworks/Java/spring/target/hello-spring-1.0-SNAPSHOT.jar app.jar
COPY --from=build /tfb/commit /spring/tfb-commit
# As TechEmpower's spring.dockerfile: run from the extracted layout.
RUN java -Djarmode=tools -jar app.jar extract
ENV PORT=8080 DB_HOST=postgres DB_PORT=5432 DB_POOL=256 SPRING_PROFILE=jdbc JVM_OPTS=""
EXPOSE 8080
# JVM flags from TechEmpower's spring.dockerfile; logging off; pool and port from the driver.
ENTRYPOINT ["sh", "-c", "exec java -XX:+DisableExplicitGC -XX:+UseStringDeduplication $JVM_OPTS -Dlogging.level.root=OFF -Ddatabase.host=$DB_HOST -Ddatabase.port=$DB_PORT -Dspring.datasource.hikari.maximum-pool-size=$DB_POOL -Dserver.port=$PORT -jar app/app.jar --spring.profiles.active=$SPRING_PROFILE"]
