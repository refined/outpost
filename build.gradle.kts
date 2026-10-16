import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Built inside Docker with the official gradle:9.8.1-jdk21 image (see Dockerfile); no wrapper is needed.

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
    implementation("com.tigerbeetle:tigerbeetle-java:0.17.9") // optional engine: LEDGER_ENGINE=TIGERBEETLE
    implementation("org.flywaydb:flyway-core:$flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:$flyway")
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.5")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

tasks.test {
    useJUnitPlatform()
    // One line per test: in Docker there is no HTML report to open, so the console is the report.
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
