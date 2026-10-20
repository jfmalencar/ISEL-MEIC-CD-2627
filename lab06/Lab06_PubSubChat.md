# Lab 3b — PubSubChat: um chat com publish/subscribe

## Objetivos

- Usar a API Java `amqp-client` para publicar e consumir mensagens no RabbitMQ.
- Perceber como o **tipo de exchange** e os **bindings** determinam quem recebe cada mensagem.
- Construir, em variantes progressivas, uma aplicação de chat entre vários utilizadores:
  - **V1:** todos recebem todas as mensagens (exchange **fanout**);
  - **V2:** cada utilizador só recebe as mensagens dos temas que subscreveu (exchange **direct**);
  - **V3:** os temas têm subtemas e as subscrições usam padrões, como "todo o tipo de desporto" (echange **topic**);
  - **V4:** uma mensagem pode ter vários temas e cada utilizador escolhe se quer as que têm *algum* ou *todos* os seus temas (exchange **headers**).

## Regra do laboratório: realiza primeiro o esquema e depois o código

Em cada variante, **antes de escrever código**, desenhe o esquema da solução (em papel ou numa ferramenta à escolha). O esquema deve incluir:

- os **clientes** (pelo menos três: Alice, Bob e Carol), indicando quem publica e quem consome;
- o **exchange**, com nome e tipo;
- as **filas**, indicando quem as cria e a que cliente pertencem;
- os **bindings**, com a binding key ou os headers de cada um;
- o **percurso de uma mensagem de exemplo**, desde quem a envia até cada cliente que a recebe.

**Terminologia.** A **routing key** pertence à **mensagem** e é indicada no `basicPublish(exchange, routingKey, …)`. A **binding key** pertence ao **binding** e é indicada no `queueBind(fila, exchange, bindingKey)`; na API Java este parâmetro também se chama `routingKey`, mas o papel é outro. O exchange decide o destino da mensagem comparando a routing key com a binding key de cada binding, de acordo com o seu tipo.

Na Variante 1 do chat é apresentado um esquema de exemplo.

## Preparação

- **Broker:** a correr em localhost. O URI de ligação é `amqp://<user>:<pass>@localhost:5672`.
- **Projeto base:** `PubSubChat.zip` Gradle disponível no Github.
- Façam uma classe por variante (`chat.ChatV1`, `chat.ChatV2`, `chat.ChatV3`, `chat.ChatV4`), cada uma a partir de uma cópia da anterior.
- Em **Run → Edit Configurations…**:
  - ponham o URI do broker em *Program arguments*;
  - ativem **Allow multiple instances** (em *Modify options*).
- Para testar, corram **três instâncias** do cliente, uma por utilizador.

### Esqueleto do cliente

O cliente faz duas coisas ao mesmo tempo: consome da sua fila (as mensagens chegam num *callback*) e lê do teclado o que o utilizador quer enviar.

```java
package chat;

import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ChatV1 {
    public static void main(String[] args) throws Exception {
        String uri = args.length > 0 ? args[0] : "amqp://guest:guest@localhost:5672";
        Scanner in = new Scanner(System.in);
        System.out.print("Nome: ");
        String nome = in.nextLine();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        try (Connection conn = factory.newConnection(); Channel ch = conn.createChannel()) {

            // TODO 1: declarar o exchange

            // Fila temporária: nome gerado pelo broker, exclusiva desta ligação,
            // apagada quando o cliente termina.
            String fila = ch.queueDeclare().getQueue();

            // TODO 2: criar o(s) binding(s) da fila ao exchange

            ch.basicConsume(fila, true, (tag, msg) -> {
                String texto = new String(msg.getBody(), StandardCharsets.UTF_8);
                System.out.println(texto);                 // TODO 3: mostrar também o tema, quando existir
            }, tag -> {});

            System.out.println("Escreva mensagens (/sair para terminar)");
            while (true) {
                String linha = in.nextLine();
                if (linha.equals("/sair")) break;
                // TODO 4: publicar a mensagem, no formato "[nome] texto"
            }
        }
    }
}
```

---

## Variante 1 — Sala única: todos recebem tudo

**Requisito:** qualquer mensagem enviada por um utilizador é recebida por todos os utilizadores ligados.

1. **Esquema.** Desenhem a solução com um exchange `chat.todos` do tipo **fanout** e três clientes ligados.

   **Esquema de exemplo** (percurso da mensagem `olá` enviada pela Alice):

   ![Esquema da Variante 1: exchange fanout chat.todos com três filas](Lab06_V1_fanout.png)

   - **Clientes:** Alice, Bob e Carol são, cada um, publicador e consumidor. Só se mostra o envio da Alice, mas o Bob e a Carol publicam da mesma forma, no mesmo exchange.
   - **Exchange:** `chat.todos`, tipo `fanout`. Cada cliente declara-o ao arrancar.
   - **Filas:** uma por cliente, criada pelo próprio com `queueDeclare()`. O nome é gerado pelo broker (`amq.gen-…`), e a fila é exclusiva e apagada quando o cliente sai.
   - **Bindings:** um por fila, com binding key `""`. Num fanout a routing key da mensagem e a binding key são ignoradas.
   - **Percurso:** (1) a Alice publica em `chat.todos`; (2) o exchange copia a mensagem para **todas** as filas ligadas; (3) cada cliente recebe-a no callback do `basicConsume`. A Alice também recebe a sua própria mensagem.

2. **Implementação** (`ChatV1`):
   - todos os clientes declaram o exchange `chat.todos` (fanout); a declaração é idempotente, por isso não importa quem arranca primeiro;
   - cada cliente liga a sua fila temporária ao exchange (binding key `""`);
   - cada linha escrita é publicada em `chat.todos` com o corpo `[nome] texto`.
3. **Teste** com três clientes e, durante o teste, observem na UI de gestão as páginas *Queues* e *Exchanges → chat.todos → Bindings*.
4. **Perguntas:**
   - Quantas filas existem quando sair um dos clientes (com `/sair`)?
   - Quem envia recebe a própria mensagem? Porquê? Como evitariam mostrá-la, sem mexer no broker?
   - Assuma que a a Carol liga-se depois de a Alice enviar uma mensagem. A Carol recebe-a? Porquê?

---

## Variante 2 — Temas com um exchange direct

**Requisito:** os temas disponíveis são `desporto`, `cinema`, `tecnologia` e `economia`.
- Ao arrancar, cada utilizador indica os temas que lhe interessam, por exemplo `desporto,tecnologia`.
- Para enviar, escreve `tema: texto`, por exemplo `desporto: o jogo começa às 20h`.
- Cada utilizador só recebe as mensagens dos temas que subscreveu.

1. **Esquema.** Desenhem a solução com um exchange `chat.temas` do tipo **direct** e com:
   - Alice: `desporto`;
   - Bob: `desporto` e `tecnologia`;
   - Carol: `cinema`.

   Indiquem todos os bindings e o percurso da mensagem `tecnologia: saiu uma nova versão do Java`. Mostrem ao docente.
2. **Implementação** (`ChatV2`):
   - a fila de cada cliente tem **um binding por tema subscrito**, com a binding key igual ao tema;
   - a mensagem é publicada com a routing key igual ao tema;
   - ao receber, mostrem o tema, que está em `msg.getEnvelope().getRoutingKey()`.
3. **Perguntas:**
   - Quantos bindings tem a fila do Bob?
   - O que acontece a uma mensagem `politica: …`, um tema que ninguém subscreveu?
   - Uma notícia é ao mesmo tempo de `economia` e de `desporto`. Com este exchange, como a enviariam? O que recebe um utilizador que subscreveu os dois temas?

---

## Variante 3 — Subtemas e padrões com um exchange topic

**Requisito:** os temas passam a ter subtemas, escritos com pontos: `desporto.futebol`, `desporto.tenis`, `tecnologia.java`, `tecnologia.ia.modelos`, …
- Para enviar, escreve-se `subtema: texto`, por exemplo `desporto.futebol: o jogo começa às 20h`.
- Ao arrancar, cada utilizador indica as suas subscrições, que podem ser **padrões**:
  - `*` substitui exatamente **uma** palavra;
  - `#` substitui **zero ou mais** palavras.

1. **Esquema.** Desenhem a solução com um exchange `chat.subtemas` do tipo **topic** e com:
   - Alice: `desporto.*`;
   - Bob: `*.futebol` e `tecnologia.#`;
   - Carol: `desporto.tenis`.

   Indiquem todos os bindings. Mostrem ao docente.
2. **Prever antes de implementar.** Preencham a tabela com ✓ ou ✗:

   | Routing key             | Alice | Bob | Carol |
   | ----------------------- | ----- | --- | ----- |
   | `desporto.futebol`      |       |     |       |
   | `desporto.tenis`        |       |     |       |
   | `tecnologia.ia.modelos` |       |     |       |
   | `desporto`              |       |     |       |

3. **Implementação** (`ChatV3`, a partir de uma cópia da `ChatV2`):
   - o exchange passa a ser do tipo **topic**;
   - cada subscrição (padrão) dá origem a um binding;
   - o resto do código fica igual: a mensagem continua a ser publicada com a routing key escrita pelo utilizador.
4. **Teste** com os três clientes do esquema e confirmem a tabela.
5. **Perguntas:**
   - O que mudou no código em relação à `ChatV2`? O que permite concluir sobre a relação entre os exchanges direct e topic?
   - Se o Bob subscrever também `desporto.#`, quantas cópias recebe da mensagem `desporto.futebol`, que corresponde a dois dos seus bindings?
   - Uma notícia é de `economia` e de `desporto`. Que routing key lhe dariam? Que problema surge se um emissor escrever `economia.desporto` e outro `desporto.economia`?

---

## Variante 4 — Vários temas por mensagem com um exchange headers

**Requisito:**
- Uma mensagem pode ter **vários temas**. Para enviar, escreve-se `economia,desporto: o clube entrou em bolsa`.
- Ao arrancar, cada utilizador indica os seus temas e o modo de receção:
  - **any**: recebe as mensagens que tenham **pelo menos um** dos seus temas;
  - **all**: recebe só as mensagens que tenham **todos** os seus temas.
- Uma mensagem é recebida **uma única vez** por cada utilizador, mesmo que tenha vários temas que lhe interessam.

**Representação dos temas nos headers:** cada tema é um header com o valor `"sim"`. Por exemplo, a mensagem acima tem os headers `economia = sim` e `desporto = sim`.

1. **Esquema.** Desenhem a solução com um exchange `chat.noticias` do tipo **headers** e com:
   - Alice: `economia, desporto`, modo **any**;
   - Bob: `economia, desporto`, modo **all**;
   - Carol: `cinema`, modo **any**.

   Indiquem os argumentos de cada binding (`x-match` e headers).
2. **Prever antes de implementar.** Preencham a tabela com ✓ ou ✗:

   | Mensagem (temas)     | Alice | Bob | Carol |
   | -------------------- | ----- | --- | ----- |
   | `economia`           |       |     |       |
   | `economia, desporto` |       |     |       |
   | `cinema, desporto`   |       |     |       |
   | `tecnologia`         |       |     |       |

3. **Implementação** (`ChatV4`):
   - a fila de cada cliente tem **um único binding**, com a binding key `""` e os argumentos `x-match` (`any` ou `all`) e um header por tema subscrito;
   - a mensagem é publicada com a routing key `""` e os headers nas propriedades (`AMQP.BasicProperties.Builder().headers(...)`);
   - ao receber, mostrem os temas a partir de `msg.getProperties().getHeaders()`.
4. **Teste** com os três clientes do esquema e confirmem a tabela.
5. **Perguntas:**
   - Porque é que, nesta variante, quem subscreveu `economia` e `desporto` recebe a notícia `economia,desporto` só uma vez, ao contrário da variante 2?
   - Que vantagem tem a variante 2 sobre esta? (Pista: o que é preciso para acrescentar um tema novo em cada uma.)

---


## Extras (opcional)

- **Mudar de interesses sem reiniciar** (variante 2): comandos `/sub tema` e `/unsub tema`, com `queueBind` e `queueUnbind`.
- **Mensagens privadas:** um exchange `chat.privado` do tipo direct, em que cada cliente liga a sua fila com o seu próprio nome como binding key. O comando `@Bob: olá` envia só para o Bob. Desenhem primeiro o esquema.

---


