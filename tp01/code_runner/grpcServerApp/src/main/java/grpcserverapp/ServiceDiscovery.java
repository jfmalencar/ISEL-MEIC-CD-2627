package grpcserverapp;

import contracts.discovery.DiscoveryGrpc;
import contracts.discovery.GetServerResult;
import contracts.discovery.ProtoVoid;
import contracts.registration.RegisterGrpc;
import contracts.registration.RegisterRequest;
import contracts.registration.RegisterResult;
import io.grpc.stub.StreamObserver;

public class ServiceDiscovery extends DiscoveryGrpc.DiscoveryImplBase {

    RedisStorage storage;
    Integer serversRegistered = 0;

    public ServiceDiscovery(int svcPort) {
        System.out.println("Service is available on port:" + svcPort);
        storage = new RedisStorage();
        storage.start();
    }

    /*@Override
    public void getServer(ProtoVoid obj, StreamObserver<GetServerResult> responseObserver) {
        try {
            storage.set("servers:" + serversRegistered, ipAddress + ":" + Integer.toString(atPort), null);

            RegisterResult result = RegisterResult.newBuilder()
                    .setRedisAddress(storage.publicAddress + ":" + storage.port)
                    .build();
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }
     */
}
