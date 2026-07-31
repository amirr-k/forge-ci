// libs/core: graph, planning, hashing, scheduling, state machines — no Spring dependency.

dependencies {
    testImplementation(project(":libs:test-support"))
}

tasks.named<Test>("test") {
    // CommandExecutionSecurityTest needs a variable that exists in the test JVM's environment and
    // is never declared by the task it runs — otherwise "the task didn't see it" proves nothing
    environment("FORGE_TEST_SECRET", "must-never-reach-a-task")
}
