plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

sourceSets {
    main {
        resources.srcDir(rootProject.file("db"))
    }
}

dependencies {
    implementation(project(":libs:cache"))
    implementation(project(":libs:protocol"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.mysql:mysql-connector-j")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    implementation(platform("software.amazon.awssdk:bom:2.29.16"))
    implementation("software.amazon.awssdk:s3")
    implementation("org.springframework.kafka:spring-kafka")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(project(":libs:test-support"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.1"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:minio")
    testImplementation("org.testcontainers:kafka")
}

tasks.named<Jar>("jar") {
    enabled = false
}

// tests tagged "integration" need Docker (Testcontainers MySQL + MinIO); split out so a plain
// `test` run stays fast and Docker-free, matching every other module's tests.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Runs the Testcontainers-backed integration test suite (MySQL, S3-compatible storage)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter("test")
}

tasks.named("check") {
    dependsOn("integrationTest")
}
