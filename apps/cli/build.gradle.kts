plugins {
    application
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:config"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    applicationName = "forge"
    mainClass.set("dev.forgeci.cli.Main")
}

tasks.named<JavaExec>("run") {
    // no bundled demo repo yet (phase 1/7) — point the manual run command at the
    // same fixture graph libs/config's parser tests use.
    workingDir = rootProject.file("libs/config/src/test/resources/fixtures/demo-project")
}
