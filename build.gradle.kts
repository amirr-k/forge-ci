plugins {
    id("com.diffplug.spotless") version "6.25.0" apply false
    id("com.github.spotbugs") version "6.0.18" apply false
}

allprojects {
    group = "dev.forgeci"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.github.spotbugs")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            // AOSP: 4-space indent, matching the style already used throughout this codebase —
            // Google's default style is 2-space and would reformat every existing line for nothing
            googleJavaFormat("1.22.0").aosp()
            removeUnusedImports()
        }
    }

    // static analysis on production code only — test code trades rigor for readable fixtures,
    // and its findings would just be noise here
    configure<com.github.spotbugs.snom.SpotBugsExtension> {
        ignoreFailures.set(false)
        effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
        reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    }
    tasks.matching { it.name == "spotbugsTest" }.configureEach { enabled = false }
    tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
        reports.create("html") { required.set(true) }
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        // a failing test has to name itself even under `gradle -q`, which is how forge runs these
        // tasks — otherwise a CI failure reports only "exit code 1" with no way to diagnose it
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }

        // a per-test-method safety net: phase 9 found a test whose own HTTP client timeout didn't
        // reliably fire against a streaming response, hanging for 10+ minutes instead of failing in
        // seconds — every legitimate test in this repo (including the longest polling loops) finishes
        // well under two minutes, so this only ever turns a silent CI stall into a fast, actionable
        // timeout failure naming the test that hung
        systemProperty("junit.jupiter.execution.timeout.default", "2m")
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
