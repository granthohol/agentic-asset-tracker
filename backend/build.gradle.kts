plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

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
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}