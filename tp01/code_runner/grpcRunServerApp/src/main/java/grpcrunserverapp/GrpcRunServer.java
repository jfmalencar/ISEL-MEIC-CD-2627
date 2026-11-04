package grpcrunserverapp;

// generic ServerApp for hosting grpcService

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import contracts.registration.RegisterGrpc;
import contracts.registration.RegisterRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import redis.clients.jedis.RedisClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GrpcRunServer {

    private static String runServerIP = "localhost";
    private static int runServerPort = 8001;
    private static String managerServerIP = "localhost";
    private static int managerServerPort = 8000;
    private static final int MAX_CONTAINERS = 5;


    public static void main(String[] args) {
        try {
            if (args.length == 4){
                runServerIP = args[0];
                runServerPort = Integer.parseInt(args[1]);
                managerServerIP = args[2];
                managerServerPort = Integer.parseInt(args[3]);

            }else {
                System.out.println("Arguments passed != 4, using default values!");
            }

            DockerClient docker = createDockerClient();
            pullIfMissing(docker);

            ExecutorService executor = Executors.newFixedThreadPool(MAX_CONTAINERS);

            Path baseDir = Path.of("requests").toAbsolutePath().normalize();
            Files.createDirectories(baseDir);

            ExecutionService executionService = new ExecutionService(runServerIP + ":" + runServerPort, docker,  executor, baseDir);
            io.grpc.Server svc = ServerBuilder.forPort(runServerPort)
                    // Add one or more services.
                    // The Server can host many services in same TCP/IP port
                    .addService(executionService)
                    .build();
            svc.start();
            System.out.println("Server started with IP " + runServerIP + " on Port " + runServerPort);

            ManagedChannel managerChannel = ManagedChannelBuilder.forAddress(managerServerIP, managerServerPort).usePlaintext().build();
            RegisterGrpc.RegisterBlockingStub registerBlockingStub = RegisterGrpc.newBlockingStub(managerChannel);
            String redisAddress;
            try {
                redisAddress = registerBlockingStub.register(
                        RegisterRequest.newBuilder()
                                .setIpAddress(runServerIP)
                                .setPort(String.valueOf(runServerPort))
                                .build()
                ).getRedisAddress();
            } catch (StatusRuntimeException e) {
                System.out.println("Failed to connect to manager: " + e.getStatus().getCode());
                System.out.println(e.getStatus().getDescription());
                svc.shutdownNow();
                executor.shutdownNow();
                docker.close();
                return;
            } finally {
                managerChannel.shutdownNow();
            }
            System.out.println("Redis Address " + redisAddress);
            String[] addresses = redisAddress.split(":");
            String redisIP = addresses[0];
            int redisPort = Integer.parseInt(addresses[1]);
            RedisClient redis = RedisClient.builder().hostAndPort(redisIP, redisPort).build();

            executionService.setRedis(redis);
            System.out.println("ManagerServer with IP " + managerServerIP + " on Port " + managerServerPort);
            // Java virtual machine shutdown hook
            // to capture normal or abnormal exits
            Runtime.getRuntime().addShutdownHook(new ShutdownHook(svc, executor, docker, redis));


            // Waits for the server to become terminated
            svc.awaitTermination();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }



    private static DockerClient createDockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, http);
    }

    private static void pullIfMissing(DockerClient docker)
            throws InterruptedException {
        try {
            docker.inspectImageCmd(ExecutionService.IMAGE + ":" + ExecutionService.TAG).exec();
        } catch (NotFoundException e) {
            System.out.println("Pulling " + ExecutionService.IMAGE + ":" + ExecutionService.TAG + " ...");
            docker.pullImageCmd(ExecutionService.IMAGE).withTag(ExecutionService.TAG)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
        }
    }
}
