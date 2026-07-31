plugins {
    application
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:cache"))
    implementation(project(":libs:protocol"))
    testImplementation(project(":libs:test-support"))
}

application {
    applicationName = "worker"
    mainClass.set("dev.forgeci.worker.WorkerMain")
}
