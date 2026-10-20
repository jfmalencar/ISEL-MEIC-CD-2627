package chat;

import com.rabbitmq.client.*;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * PubSubChat — Variante 1 (fanout): todos recebem todas as mensagens.
 *
 * Uso: java -cp PubSubChat-1.0-jar-with-dependencies.jar chat.ChatV1 amqp://<user>:<pass>@<IP-VM>:5672
 */
public class ChatV1 {
    static final String EXCHANGE_NAME = "chat.todos";

    public static void main(String[] args) throws Exception {
        String uri = args.length > 0 ? args[0] : "amqp://guest:guest@localhost:5672";
        Scanner in = new Scanner(System.in);
        System.out.print("Nome: ");
        String nome = in.nextLine();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        try (Connection conn = factory.newConnection(); Channel ch = conn.createChannel()) {

            // TODO 1: declarar o exchange
            ch.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.FANOUT,false);

            // Fila temporária: nome gerado pelo broker, exclusiva desta ligação,
            // apagada quando o cliente termina.
            String fila = ch.queueDeclare().getQueue();

            // TODO 2: criar o(s) binding(s) da fila ao exchange
            ch.queueBind(fila,EXCHANGE_NAME,"");

            ch.basicConsume(fila, true, (tag, msg) -> {
                String texto = new String(msg.getBody(), StandardCharsets.UTF_8);
                System.out.println(texto);                 // TODO 3: mostrar também o tema, quando existir
            }, tag -> {});

            System.out.println("Escreva mensagens (/sair para terminar)");
            while (true) {
                String linha = in.nextLine();
                if (linha.equals("/sair")) break;
                // TODO 4: publicar a mensagem, no formato "[nome] texto"
                ch.basicPublish( EXCHANGE_NAME, "", null, ("[" + nome + "] " + linha).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
