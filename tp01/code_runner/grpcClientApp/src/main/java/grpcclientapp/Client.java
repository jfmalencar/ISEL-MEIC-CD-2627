package grpcclientapp;

import com.google.protobuf.ByteString;
import contracts.discovery.DiscoveryGrpc;
import contracts.discovery.GetServerResult;
import contracts.discovery.ProtoVoid;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import contracts.execution.*;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Client {
    private static String managerIP = "localhost";
    private static int managerPort = 8000;
    private static ExecutionGrpc.ExecutionBlockingStub blockingStub;
    private static ExecutionGrpc.ExecutionStub noBlockStub;
    private static final Scanner scan = new Scanner(System.in);


    public static void main(String[] args) {
        try {
            if (args.length == 2) {
                managerIP = args[0]; managerPort = Integer.parseInt(args[1]);
            }
            System.out.println("connect to " + managerIP + ":" + managerPort);
            ManagedChannel managerChannel = ManagedChannelBuilder.forAddress(managerIP, managerPort)
                    .usePlaintext()
                    .build();
            DiscoveryGrpc.DiscoveryBlockingStub discoveryBlockingStub = DiscoveryGrpc.newBlockingStub(managerChannel);

            GetServerResult serverResult =  discoveryBlockingStub.getServer(ProtoVoid.newBuilder().build());
            String runServerIP = serverResult.getServerAddress();
            int runServerPort = Integer.parseInt(serverResult.getPort());
            managerChannel.shutdown();
            System.out.println("run server received " + runServerIP + ":" + runServerPort);

            ManagedChannel serverChannel = ManagedChannelBuilder.forAddress(runServerIP, runServerPort).usePlaintext().build();
            blockingStub = ExecutionGrpc.newBlockingStub(serverChannel);
            noBlockStub  = ExecutionGrpc.newStub(serverChannel);

            boolean end = false;
            while (!end) {
                try {
                    int option = Menu();
                    switch (option) {
                        case 1:
                            submitCall(); break;
                        case 2:
                            getResultCall(); break;

                        case 99:   serverChannel.shutdown(); System.exit(0);
                    }
                } catch (Exception ex) {
                    System.out.println("Execution call Error  !");
                    ex.printStackTrace();
                }
            }
            read("prima enter to end", scan);
        } catch (Exception ex) {
            System.out.println("Unhandled exception");
            ex.printStackTrace();
        }
    }

    static void submitCall() throws InterruptedException {
        String filePath = read("Enter program file path", scan);
        if (!Files.exists(Path.of(filePath))) {
            System.out.println("Program File not found");
            return;
        }
        String inputFilePath = read("Enter input file path", scan);
        if (!inputFilePath.isEmpty() && !Files.exists(Path.of(inputFilePath))) {
            System.out.println("Input File not found");
            return;
        }
        CountDownLatch countDownLatch = new CountDownLatch(1);
        StreamObserver<RequestId> responseObserver = new StreamObserver<RequestId>() {
            @Override
            public void onNext(RequestId requestId) {
                System.out.println("request ID: " + requestId.getId());
            }
            @Override
            public void onError(Throwable t) {
                System.out.println("request Error: " + t.getMessage());
                countDownLatch.countDown();
            }
            @Override
            public void onCompleted() {
                countDownLatch.countDown();
            }
        };
        StreamObserver<FileBlock> requestObserver = noBlockStub.submit(responseObserver);
        sendFile(filePath, requestObserver, false);
        if (!inputFilePath.isEmpty()) sendFile(inputFilePath, requestObserver, true);


        requestObserver.onCompleted();
        countDownLatch.await();
    }

    private static void sendFile(String filePath, StreamObserver<FileBlock> requestObserver, boolean isInput) {
        try (InputStream in = Files.newInputStream(Path.of(filePath))) {
            byte[] buffer = new byte[32 * 1024];   // 32 KB
            int n;
            while ((n = in.read(buffer)) != -1) {
                // o buffer tem n bytes válidos do índice 0 a n-1
                ByteString chunk = ByteString.copyFrom(buffer, 0, n);
                FileBlock.Builder builder = FileBlock.newBuilder();
                if (isInput) {
                    builder.setInputChunk(chunk);
                } else {
                    builder.setProgramChunk(chunk);
                }
                requestObserver.onNext(builder.build());
            }
        } catch (IOException e) {
            requestObserver.onError(e); // para o servidor não ficar a espera de novos blocos quando ha erro
            throw new RuntimeException(e);
        }
    }


    static void getResultCall() {
        try {
            String uuid = (read("Enter Request UUID: ", scan));
            Iterator<ResultBlock> result =  blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS).getResult(RequestId.newBuilder().setId(uuid).build());
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final ByteArrayOutputStream error = new ByteArrayOutputStream();
            RequestStatus state = RequestStatus.UNKNOWN;
            while (result.hasNext()) {
                ResultBlock resultBlock = result.next();
                switch (resultBlock.getContentCase()) {
                    case STATUS -> state = resultBlock.getStatus();
                    case OUTPUT_CHUNK ->  output.writeBytes(resultBlock.getOutputChunk().toByteArray());
                    case ERROR_CHUNK ->   error.writeBytes(resultBlock.getErrorChunk().toByteArray());
                }
            }
            switch (state) {
                case UNKNOWN ->  System.out.println("Request not found or already checked");
                case PENDING ->  System.out.println("Request still pending, try again later");
                case COMPLETE, TIMEOUT, ERROR -> {
                    System.out.println("Request State = " + state);
                    System.out.println("Output result: " + output);
                    System.out.println("Error result: " + error);
                }
                default -> System.out.println("Unknown State: " + state);
            }

        }catch (StatusRuntimeException e){
            System.err.println(e.getStatus().getCode());
            System.err.println(e.getStatus().getDescription());
        }

    }

    private static int Menu() {
        int op;
        do {
            System.out.println();
            System.out.println("    MENU");
            System.out.println("1. Submit program");
            System.out.println("2. Get result");
            System.out.println("99 - Exit");
            System.out.println();
            System.out.println("Choose an Option?");
            try {
                op = Integer.parseInt(scan.nextLine());
            }catch (NumberFormatException e) {
                System.out.println("Invalid Option");
                op = -1;
            }
        } while (!((op >= 1 && op <= 2) || op == 99));
        return op;
    }

    private static String read(String msg, Scanner input) {
        System.out.println(msg);
        return input.nextLine();
    }
}
