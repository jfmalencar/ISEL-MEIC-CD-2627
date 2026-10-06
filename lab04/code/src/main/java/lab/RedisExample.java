package lab;

import redis.clients.jedis.RedisClient;
import java.util.Map;
import java.util.UUID;

public class RedisExample {

    public static void main(String[] args) {

        RedisClient jedis = RedisClient.create("localhost", 6000);

        String id = UUID.randomUUID().toString();
        String key = "pedido:" + id;

        jedis.hset(key, Map.of(
                "estado", "PENDENTE",
                "servidor", "10.0.0.5:8500"
        ));

        jedis.expire(key, 24 * 3600);

        System.out.println(jedis.hgetAll(key));

        jedis.hset(key, Map.of(
                "estado", "CONCLUIDO",
                "exitCode", "0",
                "stdout", "42",
                "stderr", ""
        ));

        System.out.println(jedis.hgetAll(key));
        jedis.close();
    }
}