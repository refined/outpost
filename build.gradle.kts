import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "io.github.refined"
version = "0.1.0-SNAPSHOT"

val ktor = "3.6.0"
val flyway = "13.10.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

application {
    mainClass = "io.github.refined.morledger.ApplicationKt"
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:$flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:$flyway")
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.5")
}

testing {
    suites {
        withType<JvmTestSuite>().configureEach {
            useJUnitJupiter("6.1.3")
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test:2.4.21")
            }
        }

        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                implementation("io.ktor:ktor-server-test-host:$ktor")
                implementation("org.testcontainers:testcontainers-postgresql:2.0.5")
            }
            targets.configureEach {
                testTask.configure { shouldRunAfter(tasks.test) }
            }
        }
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}

tasks.withType<Test>().configureEach {
    // One line per test: in Docker there is no HTML report to open, so the console is the report.
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
