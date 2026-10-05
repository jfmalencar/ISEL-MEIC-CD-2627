package com.example;

import com.example.grpc.GreetingServiceGrpc;
import com.example.grpc.HelloRequest;
import com.example.grpc.HelloResponse;
import io.grpc.stub.StreamObserver;

public class GreetingServiceImpl
        extends GreetingServiceGrpc.GreetingServiceImplBase {

    @Override
    public void sayHello(
            HelloRequest request,
            StreamObserver<HelloResponse> responseObserver) {

        StringBuilder name = new StringBuilder(request.getName());

        for (int i = 1; i < request.getRepeat(); i++) {
            name.append(", ").append(request.getName());
        }

        HelloResponse response = HelloResponse.newBuilder()
                .setMessage("Hello, " + name + "!")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
