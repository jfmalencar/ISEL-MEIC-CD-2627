package chat;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

/**
 * PubSubChat — Variante 2 (direct): cada utilizador só recebe os temas que subscreveu.
 *
 * Uso: java -cp PubSubChat-1.0-jar-with-dependencies.jar chat.ChatV2 amqp://<user>:<pass>@<IP-VM>:5672
 */
public class ChatV2 {
    static final String EXCHANGE_NAME = "chat.temas";
    static final List<String> TEMAS = List.of("desporto", "cinema", "tecnologia", "economia");

    public static void main(String[] args) throws Exception {
        String uri = args.length > 0 ? args[0] : "amqp://guest:guest@localhost:5672";
        Scanner in = new Scanner(System.in);
        System.out.print("Nome: ");
        String nome = in.nextLine();
        System.out.print("Temas " + TEMAS + ", separados por virgulas: ");
        String[] subscricoes = in.nextLine().split(",");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        try (Connection conn = factory.newConnection(); Channel ch = conn.createChannel()) {

            // TODO 1: declarar o exchange
            ch.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.DIRECT, false);

            // Fila temporária: nome gerado pelo broker, exclusiva desta ligação,
            // apagada quando o cliente termina. Uma só fila por cliente.
            String fila = ch.queueDeclare().getQueue();

            // TODO 2: um binding por tema subscrito, com a binding key igual ao tema
            for (String subscricao : subscricoes) {
                String tema = subscricao.trim();
                if (tema.isEmpty()) continue;
                ch.queueBind(fila, EXCHANGE_NAME, tema);
                System.out.println("subscrito: " + tema);
            }

            ch.basicConsume(fila, true, (tag, msg) -> {
                String texto = new String(msg.getBody(), StandardCharsets.UTF_8);
                // TODO 3: o tema da mensagem é a routing key com que foi publicada
                String tema = msg.getEnvelope().getRoutingKey();
                System.out.println(tema + " " + texto);
            }, tag -> {});

            System.out.println("Escreva mensagens no formato \"tema: texto\" (/sair para terminar)");
            while (true) {
                String linha = in.nextLine();
                if (linha.equals("/sair")) break;

                int sep = linha.indexOf(':');
                if (sep < 0) {
                    System.out.println("formato invalido, use \"tema: texto\"");
                    continue;
                }
                String tema = linha.substring(0, sep).trim();
                String texto = linha.substring(sep + 1).trim();

                // TODO 4: publicar com a routing key igual ao tema
                ch.basicPublish(EXCHANGE_NAME, tema, null,
                        ("[" + nome + "] " + texto).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
