package grpcserverapp;

import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

public class RedisStorage {

    final String publicAddress = "127.0.0.1";
    final String host = "localhost";
    final int port = 6379;

    RedisClient redis;

    public void start() {
        try {
            redis = RedisClient.builder().hostAndPort(host, port).build();
        } catch (Exception e) {
            System.err.println("Error starting Redis storage: " + e.getMessage());
        }
    }

    public void set(String key, String value, Integer expiresInSeconds) {
        if (redis == null) {
            System.err.println("Redis client not initialized");
            return;
        }
        try {
            if (expiresInSeconds != null) {
                redis.set(key, value, SetParams.setParams().ex(expiresInSeconds));
            } else {
                redis.set(key, value);
            }
        } catch (Exception e) {
            System.err.println("Error setting Redis key: " + e.getMessage());
        }
    }

    public String get(String key) {
        if (redis == null) {
            System.err.println("Redis client not initialized");
            return null;
        }
        try {
            return redis.get(key);
        } catch (Exception e) {
            System.err.println("Error getting Redis key: " + e.getMessage());
            return null;
        }
    }

    public void delete(String key) {
        if (redis == null) {
            System.err.println("Redis client not initialized");
            return;
        }
        try {
            redis.del(key);
        } catch (Exception e) {
            System.err.println("Error deleting Redis key: " + e.getMessage());
        }
    }

    /*
    public static void main(String[] args) {

        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;

        // Thread-safe, pooled client: create once and share.
        try (RedisClient redis = RedisClient.builder().hostAndPort(host, port).build()) {

            // SET key value
            redis.set("aluno:1234:nome", "Maria");

            // GET key
            String nome = redis.get("aluno:1234:nome");
            System.out.println("GET aluno:1234:nome  -> " + nome);

            // Missing key returns null
            System.out.println("GET aluno:9999:nome  -> " + redis.get("aluno:9999:nome"));

            // SET key value EX seconds
            redis.set("sessao:abc", "ativa", SetParams.setParams().ex(30));
            System.out.println("TTL sessao:abc       -> " + redis.ttl("sessao:abc") + " s");

            // DEL key
            redis.del("aluno:1234:nome");
            System.out.println("EXISTS aluno:1234:nome -> " + redis.exists("aluno:1234:nome"));
        }
    }
    */
}
