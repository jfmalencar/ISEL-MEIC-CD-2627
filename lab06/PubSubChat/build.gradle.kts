// PubSubChat — cliente de chat com RabbitMQ (Lab 3b)
plugins {
    application
}

group = "org.example"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.rabbitmq:amqp-client:5.37.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

// Compila para Java 21 (funciona com qualquer JDK >= 21)
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

application {
    mainClass = "chat." + ((findProperty("variante") as String?) ?: "ChatV1")
}

// A aplicação lê do teclado: ligar o stdin ao "gradlew run"
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Jar executável com todas as dependências (equivalente ao jar-with-dependencies do Maven)
// Uso: ./gradlew fatJar -> build/libs/PubSubChat-1.0-jar-with-dependencies.jar
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Gera um jar executável com todas as dependências."
    archiveClassifier = "jar-with-dependencies"
    manifest {
        attributes["Main-Class"] = application.mainClass
    }
    from(sourceSets.main.map { it.output })
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    // assinaturas de dependências deixam de ser válidas num jar combinado
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") {
    dependsOn(fatJar)
}

// Executar uma variante pelo Gradle:
//   ./gradlew run --args="amqp://cd:<pass>@<IP-VM>:5672" --console=plain -q            (ChatV1)
//   ./gradlew run -Pvariante=ChatV2 --args="amqp://..." --console=plain -q            (outras variantes)
