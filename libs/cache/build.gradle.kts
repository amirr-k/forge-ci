// libs/cache: cache-key computation, deterministic archives, local content-addressed storage —
// shared by cli/control-plane/worker.

dependencies {
    implementation(project(":libs:core"))
    testImplementation(project(":libs:test-support"))
}
