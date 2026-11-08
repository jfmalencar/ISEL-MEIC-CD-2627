package grpcserverapp;

// generic ServerApp for hosting grpcService

import io.grpc.ServerBuilder;

public class ManagerServer {

    private static int svcPort = 8000;
    private static String redisPublicAddress = "127.0.0.1";

    // Uso: java -jar grpcServerApp.jar [porto] [IP do Redis para os RunServers]
    public static void main(String[] args) {
        try {
            if (args.length > 0) svcPort = Integer.parseInt(args[0]);
            if (args.length > 1) redisPublicAddress = args[1];
            RedisStorage storage = new RedisStorage(redisPublicAddress);
            storage.start();
            System.out.println("Redis address announced to RunServers: "
                    + storage.publicAddress + ":" + storage.port);
            io.grpc.Server svc = ServerBuilder.forPort(svcPort)
                    // Add one or more services.
                    // The Server can host many services in same TCP/IP port
                    .addService(new ServiceRegister(svcPort, storage))
                    .addService(new ServiceDiscovery(svcPort, storage))
                    .build();
            svc.start();
            System.out.println("Server started on port " + svcPort);
            // Java virtual machine shutdown hook
            // to capture normal or abnormal exits
            Runtime.getRuntime().addShutdownHook(new ShutdownHook(svc));
            // Waits for the server to become terminated
            svc.awaitTermination();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
