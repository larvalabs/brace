# syntax=docker/dockerfile:1
# The Brace TFB app, built from this checkout's framework. Build context: the repo root
# (see compose.yml); brace.Dockerfile.dockerignore limits it to the framework and benchmark sources.

# Same pinned build image as the `brace new` scaffold (ProjectGenerator.BUILD_IMAGE).
FROM maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
# Install the framework into the image's local repo. No tests, distribution zip or sources jar.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q install -Dmaven.test.skip=true -Dassembly.skipAssembly=true \
        -Dmaven.antrun.skip=true -Dmaven.source.skip=true
COPY benchmark/pom.xml benchmark/pom.xml
COPY benchmark/src benchmark/src
RUN --mount=type=cache,target=/root/.m2 \
    version="$(sed -n 's:^    <version>\(.*\)</version>.*:\1:p' pom.xml | head -1)" && \
    mvn -q -f benchmark/pom.xml package -DskipTests -Dbrace.version="$version" && \
    echo "$version" > /src/brace-version

# JDK, not JRE: in prod mode Brace compiles the templates once at startup, which needs
# jdk.compiler. spring.Dockerfile uses the same runtime image.
FROM eclipse-temurin:25-jdk
# The checkout's commit, passed in by run-docker.sh (.git isn't in the build context).
ARG BRACE_COMMIT=unknown
WORKDIR /app
RUN echo "$BRACE_COMMIT" > /app/brace-commit
COPY --from=build /src/benchmark/target/brace-benchmark.jar /app/brace-benchmark.jar
COPY --from=build /src/brace-version /app/brace-version
# App.java resolves templates relative to the working directory.
COPY benchmark/src/main/resources/views /app/benchmark/src/main/resources/views
ENV PORT=8080 DB_HOST=postgres DB_PORT=5432 DB_POOL=256 BRACE_LOG_LEVEL=WARN JVM_OPTS=""
EXPOSE 8080
# exec: java is PID 1, which the driver reads memory from (/proc/1/status).
ENTRYPOINT ["sh", "-c", "exec java --enable-preview -Dbrace.mode=prod $JVM_OPTS -jar /app/brace-benchmark.jar"]
