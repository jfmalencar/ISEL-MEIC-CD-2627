# grpcBase-Slides (versão Gradle)

Três projetos Gradle independentes, equivalentes aos projetos Maven originais:

| Projeto         | Conteúdo                                         |
|-----------------|--------------------------------------------------|
| `grpcContract`  | `ServiceContract.proto` → stubs Java (`servicestubs`) |
| `grpcServerApp` | Servidor gRPC (`grpcserverapp.GrpcServer`)       |
| `grpcClientApp` | Cliente gRPC com menu (`grpcclientapp.Client`)   |

Requisitos: JDK 21 ou superior. Não é preciso instalar o Gradle: cada projeto
inclui o *wrapper* (`gradlew` / `gradlew.bat`, Gradle 9.7.1).

## Como funciona a dependência do contrato

O servidor e o cliente dependem de `isel.grpc:grpcContract:1.0`, tal como no Maven.
Se as três pastas estiverem lado a lado, o Gradle usa um *build composto*
(`includeBuild("../grpcContract")` no `settings.gradle.kts`) e compila o contrato
automaticamente — não é necessário o passo equivalente a `mvn install`.

Se o cliente/servidor forem usados sem a pasta do contrato ao lado, publicar
primeiro o contrato no repositório local:

    cd grpcContract
    ./gradlew publishToMavenLocal      # equivalente a: mvn install

## Gerar o jar com todas as dependências

    cd grpcServerApp
    ./gradlew shadowJar                # ou ./gradlew build (equivalente a mvn package)
    java -jar build/libs/grpcServerApp-1.0-jar-with-dependencies.jar 8000

    cd grpcClientApp
    ./gradlew shadowJar
    java -jar build/libs/grpcClientApp-1.0-jar-with-dependencies.jar localhost 8000

Também é possível executar sem gerar o jar:

    ./gradlew run --args="8000"                             # servidor
    ./gradlew run -q --console=plain --args="localhost 8000"  # cliente (menu interativo)

## Correspondência Maven → Gradle

| Maven                                          | Gradle                                                      |
|------------------------------------------------|-------------------------------------------------------------|
| `os-maven-plugin` + `protobuf-maven-plugin`    | plugin `com.google.protobuf`                                |
| dependências `compile` do contrato             | `api(...)` (plugin `java-library`)                          |
| `mvn install` do contrato                      | build composto, ou `./gradlew publishToMavenLocal`          |
| `maven-assembly-plugin` (`jar-with-dependencies`) | plugin Shadow, tarefa `shadowJar`                        |
| `containerDescriptorHandler metaInf-services`  | `mergeServiceFiles()`                                       |
| `<mainClass>` no manifest                      | `application { mainClass = ... }` + atributo `Main-Class`   |
| `maven.compiler.source/target = 21`            | `options.release = 21`                                      |
