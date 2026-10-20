# PubSubChat — projeto base do Lab 3b

1. No IntelliJ: **File → Open** e escolher a pasta `PubSubChat`. O projeto Gradle é importado automaticamente (JDK 21 ou superior).
2. Abrir `src/main/java/chat/ChatV1.java` e correr com a seta verde ao lado de `main`.
3. Em **Run → Edit Configurations…**:
   - em *Program arguments*, pôr o URI do broker: `amqp://<user>:<pass>@<IP-VM>:5672`;
   - em *Modify options*, ativar **Allow multiple instances**, para poderem correr três clientes ao mesmo tempo.
