package grpcserverapp;

import contracts.registration.RegisterRequest;
import contracts.registration.RegisterResult;
import contracts.registration.RegisterGrpc;
import io.grpc.stub.StreamObserver;

public class ServiceRegister extends RegisterGrpc.RegisterImplBase{

    RedisStorage storage;

    public ServiceRegister(int svcPort, RedisStorage storage) {
        System.out.println("Service is available on port:" + svcPort);
        this.storage = storage;
    }

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResult> responseObserver) {
        try {
            String ipAddress = request.getIpAddress();
            int atPort = Integer.parseInt(request.getPort());

            if (ipAddress.isBlank() || atPort < 1 || atPort > 65535) {
                throw new IllegalArgumentException("invalid RunServer address");
            }
            storage.addRunServer(ipAddress + ":" + atPort);

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
