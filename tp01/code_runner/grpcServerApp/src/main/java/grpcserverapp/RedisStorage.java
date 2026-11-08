package grpcserverapp;

import contracts.execution.RequestStatus;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.Set;

public class RedisStorage {

    static final String RUN_SERVERS_KEY = "runservers";
    // Endereço do Redis enviado aos RunServers no registo (IP da VM do Manager visto pelas outras VMs)
    final String publicAddress;
    // Endereço usado pelo próprio Manager: o Redis corre na mesma VM
    final String host = "localhost";
    final int port = 6379;

    RedisClient redis;

    public RedisStorage(String publicAddress) {
        this.publicAddress = publicAddress;
    }

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

    public void addRunServer(String address) {
        requireRedis().sadd(RUN_SERVERS_KEY, address);
    }

    public Set<String> getRunServers() {
        Set<String> servers = requireRedis().smembers(RUN_SERVERS_KEY);
        return servers == null ? Collections.emptySet() : servers;
    }

    public int getPendingCount(String serverAddress) {
        int pending = 0;
        for (String requestKey : requireRedis().keys("request:*")) {
            if (RequestStatus.PENDING.name().equals(requireRedis().hget(requestKey, "status"))
                    && serverAddress.equals(requireRedis().hget(requestKey, "server"))) {
                pending++;
            }
        }
        return pending;
    }

    private RedisClient requireRedis() {
        if (redis == null) {
            throw new IllegalStateException("Redis client not initialized");
        }
        return redis;
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
