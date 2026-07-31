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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.mysql:mysql-connector-j")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":libs:test-support"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.1"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
}

tasks.named<Jar>("jar") {
    enabled = false
}
