import com.google.protobuf.gradle.id

plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.9.4"
}

val protobufVersion = "3.25.5"

group = "com.assettracker"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    implementation("org.springframework.boot:spring-boot-starter-kafka")

    implementation("org.springframework.boot:spring-boot-starter-json")

    implementation("com.fasterxml.jackson.core:jackson-databind")

    implementation("org.xerial:sqlite-jdbc")

    implementation("org.neo4j.driver:neo4j-java-driver:5.26.0")

    // Phase 4 WS payloads. Pin so protoc and protobuf-java stay matched.
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// proto/ lives at repo root so backend and frontend share one schema.
sourceSets {
    main {
        proto {
            srcDir("../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}