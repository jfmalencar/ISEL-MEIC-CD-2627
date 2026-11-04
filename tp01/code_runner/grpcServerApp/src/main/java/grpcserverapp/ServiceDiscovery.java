package grpcserverapp;

import contracts.discovery.DiscoveryGrpc;
import contracts.discovery.GetServerResult;
import contracts.discovery.ProtoVoid;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class ServiceDiscovery extends DiscoveryGrpc.DiscoveryImplBase {

    private final RedisStorage storage;
    private int tieBreaker;

    public ServiceDiscovery(int svcPort, RedisStorage storage) {
        System.out.println("Service is available on port:" + svcPort);
        this.storage = storage;
    }

    @Override
    public void getServer(ProtoVoid obj, StreamObserver<GetServerResult> responseObserver) {
        try {
            String server = selectServer();
            String[] address = server.split(":", 2);
            GetServerResult result = GetServerResult.newBuilder()
                    .setServerAddress(address[0])
                    .setPort(address[1])
                    .build();
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    private synchronized String selectServer() {
        Set<String> servers = storage.getRunServers();
        if (servers.isEmpty()) {
            throw Status.UNAVAILABLE
                    .withDescription("no RunServer is registered")
                    .asRuntimeException();
        }

        List<String> ordered = new ArrayList<>(servers);
        ordered.sort(Comparator.naturalOrder());
        int start = tieBreaker++ % ordered.size();
        Collections.rotate(ordered, -start);

        int lowestPending = Integer.MAX_VALUE;
        String selected = null;
        for (String server : ordered) {
            int pending = storage.getPendingCount(server);
            if (pending < lowestPending) {
                lowestPending = pending;
                selected = server;
            }
        }
        if (selected == null) {
            throw Status.UNAVAILABLE
                    .withDescription("no RunServer is available")
                    .asRuntimeException();
        }
        return selected;
    }
}
