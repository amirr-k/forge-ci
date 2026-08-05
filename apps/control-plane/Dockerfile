FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :apps:control-plane:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/apps/control-plane/build/libs/*.jar app.jar
COPY --from=build /workspace/demo/sample-monorepo /opt/forge-demo-repo
# the 150-task scale fixture the Docker-stack benchmarks run against; the sample monorepo
# above stays the demo's fixed workload
COPY --from=build /workspace/demo/scale-monorepo /opt/forge-scale-repo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
