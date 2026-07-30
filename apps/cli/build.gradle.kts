plugins {
    application
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:config"))
    implementation("info.picocli:picocli:4.7.6")
    testImplementation(project(":libs:test-support"))
}

application {
    applicationName = "forge"
    mainClass.set("dev.forgeci.cli.Main")
}

tasks.named<JavaExec>("run") {
    // ./gradlew :apps:cli:run drives the bundled demo repo; ./forge is the everyday entry point
    workingDir = rootProject.file("demo/sample-monorepo")
}
