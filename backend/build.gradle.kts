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
    // The core web tools and the built-in server
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    // Added for Phase 2/3 real-time map updates
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    
    // Fixed the test name so automated testing works later
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}