plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "isel.grpc"
version = "1.0"

val mainClassName = "grpcdemoapp.Demo"

repositories {
    mavenCentral()
    mavenLocal() // contrato publicado com "publishToMavenLocal" (ou "mvn install")
}

dependencies {
    implementation("isel.grpc:grpcContract:1.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

application {
    mainClass = mainClassName
}

// Jar com todas as dependências: ./gradlew shadowJar
// Resultado: build/libs/Demo.jar
tasks.shadowJar {
    archiveFileName = "Demo.jar"
    manifest {
        attributes["Main-Class"] = mainClassName
    }
    // O gRPC usa java.util.ServiceLoader, por isso os ficheiros META-INF/services
    // das várias dependências têm de ser concatenados. Os restantes duplicados são ignorados.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

