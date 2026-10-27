plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "isel.grpc"
version = "1.0"

val mainClassName = "grpcclientapp.Client"

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
// Resultado: build/libs/grpcClientApp-1.0-jar-with-dependencies.jar
tasks.shadowJar {
    archiveClassifier = "jar-with-dependencies"
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

// Para "./gradlew run": o menu do cliente lê do teclado (System.in)
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
