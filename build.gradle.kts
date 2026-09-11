plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.sidpatchy"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.sidpatchy.Main")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jline:jline:4.4.3")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-tiff:3.12.0")
    implementation("tools.jackson.core:jackson-databind:3.2.2")
    implementation("tools.jackson.dataformat:jackson-dataformat-smile:3.2.2")
    implementation("io.javalin:javalin:7.2.3")
    implementation("org.slf4j:slf4j-simple:2.0.19")


    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaExec>("run") {
    mainClass.set("com.sidpatchy.Main")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.sidpatchy.Main"
    }
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "com.sidpatchy.Main"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}