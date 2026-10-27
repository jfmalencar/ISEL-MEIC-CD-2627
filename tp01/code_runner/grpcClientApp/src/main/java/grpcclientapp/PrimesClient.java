package grpcclientapp;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import servicestubs.IntNumber;
import servicestubs.IntervalNumbers;
import servicestubs.ServiceGrpc;

import java.util.concurrent.CountDownLatch;


public class PrimesClient {
    private static String svcIP = "localhost";
    private static int svcPort = 8000;
    private static ManagedChannel channel;
    private static ServiceGrpc.ServiceStub noBlockStub;

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 2) {
            svcIP = args[0]; svcPort = Integer.parseInt(args[1]);
        }
        CountDownLatch latch = new CountDownLatch(5);

        System.out.println("connect to " + svcIP + ":" + svcPort);
        channel = ManagedChannelBuilder.forAddress(svcIP, svcPort)
                // Channels are secure by default (via SSL/TLS).
                // For the example we disable TLS to avoid
                // needing certificates.
                .usePlaintext()
                .build();
        noBlockStub = ServiceGrpc.newStub(channel);

        for (int i = 0; i < 5; i++) {
            int start = i * 100 + 1;
            int end   = (i + 1) * 100;

            IntervalNumbers pedido = IntervalNumbers.newBuilder()
                    .setStart(start).setEnd(end).build();

            noBlockStub.findPrimes(pedido, new StreamObserver<IntNumber>() {
                @Override
                public void onNext(IntNumber n) {
                    System.out.println("[" + start  + "," + end  + "] prime: " + n.getIntNumber());
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println(Status.fromThrowable(t).getCode());
                    System.out.println(Status.fromThrowable(t).getDescription());
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    System.out.println("[" + start  + "," + end  + "] complete");
                    latch.countDown();
                }
            });
        }
        latch.await();
        channel.shutdown();
    }
}
