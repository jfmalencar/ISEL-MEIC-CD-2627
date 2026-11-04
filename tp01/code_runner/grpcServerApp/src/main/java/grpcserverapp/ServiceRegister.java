package grpcserverapp;

import contracts.registration.RegisterRequest;
import contracts.registration.RegisterResult;
import contracts.registration.RegisterGrpc;
import io.grpc.stub.StreamObserver;

public class ServiceRegister extends RegisterGrpc.RegisterImplBase{

    RedisStorage storage;
    Integer serversRegistered = 0;

    public ServiceRegister(int svcPort) {
        System.out.println("Service is available on port:" + svcPort);
        storage = new RedisStorage();
        storage.start();
    }

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResult> responseObserver) {
        try {
            String ipAddress = request.getIpAddress();
            int atPort = Integer.parseInt(request.getPort());
            serversRegistered++;

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
}
