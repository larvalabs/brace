# syntax=docker/dockerfile:1
# The official TechEmpower Spring Boot implementation (frameworks/Java/spring), at the final
# commit of the TechEmpower repo (archived in March 2026), plus a Spring Data JPA (Hibernate)
# profile from ./spring-jpa, which TechEmpower never shipped. That makes it the "full ORM" Spring
# peer for Brace. TechEmpower's own profiles (JdbcTemplate, Spring Data JDBC, jOOQ) aren't used:
# hand-written SQL against Brace's Hibernate isn't a like-for-like comparison.

FROM maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS source
ARG TFB_COMMIT=57d92fbec6f8fd7431bc77326dd0484e60c96e20
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends git > /dev/null && rm -rf /var/lib/apt/lists/*
WORKDIR /tfb
# Shallow, partial, sparse clone: only this one framework's files are downloaded. The repo is
# archived, so its tip is TFB_COMMIT; the test below fails loudly if that ever stops being true.
RUN git clone -q --depth 1 --filter=blob:none --no-checkout https://github.com/TechEmpower/FrameworkBenchmarks.git . && \
    git sparse-checkout set frameworks/Java/spring && \
    git checkout -q "$TFB_COMMIT" && \
    test "$(git rev-parse HEAD)" = "$TFB_COMMIT" && \
    echo "$TFB_COMMIT" > /tfb/commit
WORKDIR /tfb/frameworks/Java/spring

FROM source AS build-jpa
COPY spring-jpa/src/ src/
COPY spring-jpa/application-jpa.yml /tmp/application-jpa.yml
# Add Spring Data JPA (Hibernate, version from the Spring Boot 3.3.5 parent) before the first
# </dependencies> (the second one belongs to the jOOQ plugin), let the jpa profile share the
# datasource settings of the JDBC profiles, and append its own settings.
RUN sed -i '0,/<\/dependencies>/s//\t\t<dependency>\n\t\t\t<groupId>org.springframework.boot<\/groupId>\n\t\t\t<artifactId>spring-boot-starter-data-jpa<\/artifactId>\n\t\t<\/dependency>\n\t<\/dependencies>/' pom.xml && \
    grep -q spring-boot-starter-data-jpa pom.xml && \
    sed -i 's/on-profile: jdbc | data-jdbc | jooq$/on-profile: jdbc | data-jdbc | jooq | jpa/' src/main/resources/application.yml && \
    grep -q 'on-profile: jdbc | data-jdbc | jooq | jpa' src/main/resources/application.yml && \
    cat /tmp/application-jpa.yml >> src/main/resources/application.yml
RUN --mount=type=cache,target=/root/.m2 mvn -q package -DskipTests

FROM eclipse-temurin:25-jdk AS runtime
WORKDIR /spring
COPY --from=source /tfb/commit /spring/tfb-commit
ENV PORT=8080 DB_HOST=postgres DB_PORT=5432 DB_POOL=256 SPRING_PROFILE=jpa JVM_OPTS=""
EXPOSE 8080
# JVM flags from TechEmpower's spring.dockerfile; logging off; pool and port from the driver.
ENTRYPOINT ["sh", "-c", "exec java -XX:+DisableExplicitGC -XX:+UseStringDeduplication $JVM_OPTS -Dlogging.level.root=OFF -Ddatabase.host=$DB_HOST -Ddatabase.port=$DB_PORT -Dspring.datasource.hikari.maximum-pool-size=$DB_POOL -Dserver.port=$PORT -jar app/app.jar --spring.profiles.active=$SPRING_PROFILE"]

FROM runtime AS spring-jpa
COPY --from=build-jpa /tfb/frameworks/Java/spring/target/hello-spring-1.0-SNAPSHOT.jar app.jar
# As TechEmpower's spring.dockerfile: run from the extracted layout.
RUN java -Djarmode=tools -jar app.jar extract
