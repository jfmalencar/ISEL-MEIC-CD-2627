package grpcclientapp;

import com.google.protobuf.ByteString;
import contracts_discovery.DiscoveryGrpc;
import contracts_discovery.GetServerResult;
import contracts_discovery.ProtoVoid;
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
    // generic ClientApp for Calling a grpc Service
    private static String managerIP = "localhost";
    private static int managerPort = 8000;
    private static ManagedChannel managerChannel;
    private static ManagedChannel  serverChannel;
    private static ExecutionGrpc.ExecutionBlockingStub blockingStub;
    private static ExecutionGrpc.ExecutionStub noBlockStub;
    private static DiscoveryGrpc.DiscoveryBlockingStub discoveryBlockingStub;
    private static Scanner scan = new Scanner(System.in);


    public static void main(String[] args) {
        try {
            if (args.length == 2) {
                managerIP = args[0]; managerPort = Integer.parseInt(args[1]);
            }
            System.out.println("connect to " + managerIP + ":" + managerPort);
            managerChannel = ManagedChannelBuilder.forAddress(managerIP, managerPort)
                    // Channels are secure by default (via SSL/TLS).
                    // For the example we disable TLS to avoid
                    // needing certificates.
                    .usePlaintext()
                    .build();
            discoveryBlockingStub = DiscoveryGrpc.newBlockingStub(managerChannel);

            GetServerResult serverResult =  discoveryBlockingStub.getServer(ProtoVoid.newBuilder().build());
            String runServerIP = serverResult.getServerAddress();
            int runServerPort = Integer.parseInt(serverResult.getPort());
            managerChannel.shutdown();
            System.out.println("run server received " + runServerIP + ":" + runServerPort);

            serverChannel = ManagedChannelBuilder.forAddress(runServerIP, runServerPort).usePlaintext().build();
            blockingStub = ExecutionGrpc.newBlockingStub(serverChannel);
            noBlockStub  = ExecutionGrpc.newStub(serverChannel);

            // Call service operations for example ping server
            boolean end = false;
            while (!end) {
                try {
                    int option = Menu();
                    switch (option) {
                        case 1:
                            submitCall(); break;
                        case 2:
                            getResultCall(); break;

//                        case 1:
//                            isAliveCall();  break;
//                        case 2:
//                            getNevenNumbersSynchronousCall(); break;
//                        case 3:
//                            getNevenNumbersAsynchronousCall(); break;
//                        case 4:
//                            addSequenceOfNumbersCall(); break;
//                        case 5:
//                            bidirectionalStreamingCall(); break;
//                        case 6:
//                            divideCall(); break;
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

//    static void isAliveCall() {
//        //ping server
//        TextMessage reply = blockingStub.isAlive(ProtoVoid.newBuilder().build());
//        System.out.println("Ping server:" + reply.getTxt());
//    }

    // 2).
//    static void divideCall() throws InterruptedException {
//        int dividend = Integer.parseInt(read("write dividend: ", new  Scanner(System.in)));
//        int divisor = Integer.parseInt(read("write divisor: ", new  Scanner(System.in)));
//        DivOperands pedido = DivOperands.newBuilder().setDividend(dividend).setDivisor(divisor).build();
//        try{
//            DivResult result = blockingStub.divide(pedido);
//            System.out.println("Quotient =" + result.getQuotient());
//            System.out.println("Remainder = " + result.getRemainder());
//
//        }catch(StatusRuntimeException ex) {
//            System.out.println(ex.getStatus().getCode());
//            System.out.println(ex.getStatus().getDescription());
//        }
//        Semaphore sem = new Semaphore(0);
//        noBlockStub.divide(pedido, new StreamObserver<DivResult>() {
//            @Override
//            public void onNext(DivResult result) {
//                System.out.println("Quotient =" + result.getQuotient());
//                System.out.println("Remainder = " + result.getRemainder());
//            }
//
//            @Override
//            public void onError(Throwable throwable) {
//                System.out.println(Status.fromThrowable(throwable).getCode());
//                System.out.println(Status.fromThrowable(throwable).getDescription());
//                sem.release();
//            }
//
//            @Override
//            public void onCompleted() {
//                sem.release();
//            }
//        });
//        System.out.println("Asynchronous call");
//        sem.acquire();
//    }
//
//    static void getNevenNumbersSynchronousCall() { // get N even numbers
//        //Synchronous blocking call
//        try {
//            int N = Integer.parseInt(
//                    read("How many even numbers?",
//                            new Scanner(System.in))
//            );
//            IntNumber intNumber=IntNumber.newBuilder().setIntNumber(N).build();
//            Iterator<IntNumber> iterator = blockingStub.getEvenNumbers(intNumber);
//            while (iterator.hasNext()) {
//                System.out.println("more one even number: " + iterator.next().getIntNumber());
//            }
//        } catch(Exception ex) {
//            System.out.println("Synchronous call error: "+ex.getMessage());
//        }
//    }
//
//    static void getNevenNumbersAsynchronousCall() throws InterruptedException { // get N even numbers
//        // Asynchronous non blocking call
//        int N = Integer.parseInt(
//                read("How many even numbers?",
//                        new Scanner(System.in)));
//        EvenNumbersStream evenStream = new EvenNumbersStream();
//        noBlockStub.getEvenNumbers(
//                IntNumber.newBuilder().setIntNumber(N).build(),
//                evenStream);
//        while (!evenStream.isCompleted()) {
//            System.out.println("Continue working until receive all even numbers");
//            Thread.sleep(1000); // Simulate processing time (1 seg)
//        }
//    }
//
//    static void addSequenceOfNumbersCall() {
//        // Add sequence of numbers
//        int N = Integer.parseInt(read("Enter N?", new Scanner(System.in)));
//        StreamObserver<IntNumber> streamNumbers = noBlockStub.addSeqOfNumbers(new StreamObserver<IntNumber>() {
//            @Override
//            public void onNext(IntNumber intNumber) {
//                System.out.println("Add total:" + intNumber.getIntNumber());
//            }
//
//            @Override
//            public void onError(Throwable throwable) {
//                System.out.println(throwable.getMessage());
//            }
//
//            @Override
//            public void onCompleted() {
//                System.out.println("Add sequence completed");
//            }
//        });
//        for (int i = 1; i <= N; i++) { // send N numbers
//            streamNumbers.onNext(IntNumber.newBuilder().setIntNumber(i).build());
//            System.out.println("sent number "+i);
//        }
//        streamNumbers.onCompleted();
//        // Note that client has sent all requests, but needs synchronization
//        // to terminate after get the final result
//    }
//
//    static void bidirectionalStreamingCall() {
//        // bidirectional streaming - Do multiple add operations
//        StreamObserver<AddOperands> streamDoAddOperations = noBlockStub.multipleAdd(new StreamObserver<AddResult>() {
//            @Override
//            public void onNext(AddResult addResult) {
//                System.out.println("Add Result ID(" + addResult.getAddID() + ")=" + addResult.getResult());
//            }
//            @Override
//            public void onError(Throwable throwable) {
//
//                System.out.println("onError:"+throwable.getMessage());
//            }
//            @Override
//            public void onCompleted() {
//                System.out.println("Add operations requests completed");
//            }
//        });
//        // Do a sequence of 20 add operations
//        for (int i = 0; i < 20; i++) {
//            int x = new Random().nextInt(10) + 1;
//            int y = new Random().nextInt(10) + 1;
//            System.out.println("Call to operation:(" + "ID" + i + "," + x + "," + y + ")");
//            AddOperands oper = AddOperands.newBuilder()
//                    .setAddID("ID" + i).setOp1(x).setOp2(y)
//                    .build();
//            streamDoAddOperations.onNext(oper);
//        }
//        streamDoAddOperations.onCompleted();
//        // Note that client has sent all requests, but needs synchronization
//        // to terminate after get all results
//    }
//
//
    private static int Menu() {
        int op;
        do {
            System.out.println();
            System.out.println("    MENU");
            System.out.println("1. Submit program");
            System.out.println("2. Get result");
//            System.out.println(" 1 - Case Unary call: server isAlive");
//            System.out.println(" 2 - Case server stream: get N even numbers: Synchronous call");
//            System.out.println(" 3 - Case server stream: get N even numbers: Asynchronous call");
//            System.out.println(" 4 - Case client stream: add sequence of numbers between 1 and N");
//            System.out.println(" 5 - Case bidirectional streaming (client and server): multiple add operations)");
//            System.out.println(" 6 - Divide: divide two numbers");
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
