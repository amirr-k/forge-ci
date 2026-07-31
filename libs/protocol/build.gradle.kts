// libs/protocol: JSON request/response shapes for the worker <-> control-plane protocol —
// shared verbatim by apps/control-plane (as Spring @RequestBody/@ResponseBody types) and
// apps/worker (via its own plain HttpClient), so both sides can never drift on field names.

plugins {
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

// records need real constructor-parameter names at runtime for Jackson to bind them without
// per-field @JsonProperty annotations.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}
