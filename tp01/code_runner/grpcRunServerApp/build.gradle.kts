plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
    java
}

group = "isel.grpc"
version = "1.0"

val mainClassName = "grpcrunserverapp.GrpcRunServer"

repositories {
    mavenCentral()
    mavenLocal() // contrato publicado com "publishToMavenLocal" (ou "mvn install")
}

val dockerJavaVersion = "3.7.1"

dependencies {
    implementation("redis.clients:jedis:8.0.1")
    implementation("com.github.docker-java:docker-java-core:${dockerJavaVersion}")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:${dockerJavaVersion}")
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
// Resultado: build/libs/grpcRunServerApp-1.0-jar-with-dependencies.jar
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
