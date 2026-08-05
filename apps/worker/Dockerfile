FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :apps:worker:installDist --no-daemon

# docker:*-cli is Alpine-based with the docker client preinstalled — the worker talks to the
# host/sibling daemon over the mounted socket, it never needs a full Docker Engine of its own.
FROM docker:26-cli
RUN apk add --no-cache openjdk21-jre-headless
WORKDIR /app
COPY --from=build /workspace/apps/worker/build/install/worker /app
COPY --from=build /workspace/demo/sample-monorepo /opt/forge-demo-repo
# the 150-task scale fixture the Docker-stack benchmarks run against; the sample monorepo
# above stays the demo's fixed workload
COPY --from=build /workspace/demo/scale-monorepo /opt/forge-scale-repo
ENTRYPOINT ["/app/bin/worker"]
