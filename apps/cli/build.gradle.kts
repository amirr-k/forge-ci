plugins {
    application
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:config"))
    implementation(project(":libs:cache"))
    implementation("info.picocli:picocli:4.7.6")
    testImplementation(project(":libs:test-support"))
}

application {
    applicationName = "cnba"
    mainClass.set("dev.cnba.cli.Main")
}

tasks.named<JavaExec>("run") {
    // ./gradlew :apps:cli:run drives the bundled demo repo; ./cnba is the everyday entry point
    workingDir = rootProject.file("demo/sample-monorepo")
}
