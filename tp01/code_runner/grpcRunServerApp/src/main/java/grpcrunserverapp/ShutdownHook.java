package grpcrunserverapp;

import com.github.dockerjava.api.DockerClient;
import redis.clients.jedis.RedisClient;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ShutdownHook extends Thread {
    io.grpc.Server svc;
    RedisClient redis;
    ExecutorService executor;
    DockerClient docker;

    public ShutdownHook(io.grpc.Server svc, ExecutorService executor, DockerClient docker, RedisClient redis) {
        this.svc = svc;
        this.redis = redis;
        this.executor = executor;
        this.docker = docker;
    }

    @Override
    public void run() {
        System.err.println("*shutdown gRPC server, because JVM is shutting down");
        try {
            svc.shutdown();
            svc.awaitTermination();
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
            docker.close();
            redis.close();
        } catch (InterruptedException | IOException e) {
            e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
    }
}
