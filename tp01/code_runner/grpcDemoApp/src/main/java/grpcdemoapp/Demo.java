package grpcdemoapp;

// Simula vários clientes concorrentes que submetem programas Python ao CodeRunner.
// Uso: java -jar Demo.jar <managerIP> <managerPort> <pastaProgramas> <copiasPorPrograma>

import com.google.protobuf.ByteString;
import contracts.discovery.DiscoveryGrpc;
import contracts.discovery.GetServerResult;
import contracts.discovery.ProtoVoid;
import contracts.execution.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

public class Demo {
    private static String managerIP = "localhost";
    private static int managerPort = 8000;
    private static String programsDir = "demo-programs";
    private static int copies = 3;

    private static final long POLL_INTERVAL_MS = 1500;
    private static final long MAX_WAIT_MS = 120_000;

    // Programas da demo: termina normalmente, erro (exceção), erro (memória), não termina
    private static final String[][] PROGRAMS = {
            {"prog.py", "input.txt"},
            {"erro.py", null},
            {"memory.py", null},
            {"loop.py", null},
    };

    record Result(String client, String program, String submitServer, String resultServer,
                  String uuid, RequestStatus status, String output, String error, long millis) {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 4) {
            managerIP = args[0];
            managerPort = Integer.parseInt(args[1]);
            programsDir = args[2];
            copies = Integer.parseInt(args[3]);
        } else {
            System.out.println("Arguments passed != 4, using default values!");
        }
        System.out.println("Manager " + managerIP + ":" + managerPort
                + " | programs in " + programsDir + " | " + copies + " copies per program");

        Path dir = Path.of(programsDir);
        List<Callable<Result>> tasks = new ArrayList<>();
        int n = 1;
        for (int c = 0; c < copies; c++) {
            for (String[] p : PROGRAMS) {
                Path prog = dir.resolve(p[0]);
                if (!Files.exists(prog)) {
                    System.out.println("Skipping missing program " + prog);
                    continue;
                }
                Path input = p[1] == null ? null : dir.resolve(p[1]);
                String name = "C" + n++;
                tasks.add(() -> runClient(name, prog, input));
            }
        }
        if (tasks.isEmpty()) {
            System.out.println("No programs found in " + dir.toAbsolutePath());
            return;
        }

        System.out.println("Launching " + tasks.size() + " concurrent clients...\n");
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        List<Future<Result>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                System.out.println("Client failed: " + e.getCause());
            }
        }
        printSummary(results);
    }

    // Um cliente: obter servidor -> submeter -> obter servidor (outra vez) -> polling do resultado
    private static Result runClient(String name, Path prog, Path input) throws Exception {
        String tag = "[" + name + " " + prog.getFileName() + "] ";
        long start = System.currentTimeMillis();

        String submitServer = getServer();
        log(tag + "submit to " + submitServer);
        String uuid;
        ManagedChannel ch = channel(submitServer);
        try {
            uuid = submit(ExecutionGrpc.newStub(ch), prog, input);
        } finally {
            ch.shutdown();
        }
        log(tag + "request id " + uuid);

        // Pede de novo um servidor ao Manager: pode ser outro RunServer,
        // o que mostra que o resultado se obtém a partir de qualquer servidor.
        String resultServer = getServer();
        if (!resultServer.equals(submitServer)) {
            log(tag + "polling result on a different server " + resultServer);
        }

        ch = channel(resultServer);
        try {
            ExecutionGrpc.ExecutionBlockingStub stub = ExecutionGrpc.newBlockingStub(ch);
            while (true) {
                Result r = getResult(stub, name, prog.getFileName().toString(),
                        submitServer, resultServer, uuid, start);
                if (r.status() != RequestStatus.PENDING) {
                    log(tag + "finished with " + r.status());
                    return r;
                }
                if (System.currentTimeMillis() - start > MAX_WAIT_MS) {
                    log(tag + "gave up waiting");
                    return r;
                }
                log(tag + "PENDING...");
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } finally {
            ch.shutdown();
        }
    }

    private static String getServer() {
        ManagedChannel ch = ManagedChannelBuilder.forAddress(managerIP, managerPort).usePlaintext().build();
        try {
            GetServerResult r = DiscoveryGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getServer(ProtoVoid.newBuilder().build());
            return r.getServerAddress() + ":" + r.getPort();
        } finally {
            ch.shutdown();
        }
    }

    private static ManagedChannel channel(String address) {
        String[] parts = address.split(":");
        return ManagedChannelBuilder.forAddress(parts[0], Integer.parseInt(parts[1])).usePlaintext().build();
    }

    private static String submit(ExecutionGrpc.ExecutionStub stub, Path prog, Path input)
            throws IOException, InterruptedException {
        CompletableFuture<String> id = new CompletableFuture<>();
        StreamObserver<FileBlock> request = stub.submit(new StreamObserver<>() {
            @Override public void onNext(RequestId value) { id.complete(value.getId()); }
            @Override public void onError(Throwable t) { id.completeExceptionally(t); }
            @Override public void onCompleted() {
                if (!id.isDone()) id.completeExceptionally(new IllegalStateException("no request id received"));
            }
        });
        try {
            sendFile(prog, request, false);
            if (input != null && Files.exists(input)) sendFile(input, request, true);
        } catch (IOException e) {
            request.onError(Status.CANCELLED.withDescription(e.getMessage()).asRuntimeException());
            throw e;
        }
        request.onCompleted();
        try {
            return id.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("submit failed: " + (e.getCause() != null ? e.getCause().getMessage() : e), e);
        }
    }

    private static void sendFile(Path file, StreamObserver<FileBlock> request, boolean isInput) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                ByteString chunk = ByteString.copyFrom(buffer, 0, n);
                FileBlock.Builder b = FileBlock.newBuilder();
                if (isInput) b.setInputChunk(chunk); else b.setProgramChunk(chunk);
                request.onNext(b.build());
            }
        }
    }

    private static Result getResult(ExecutionGrpc.ExecutionBlockingStub stub, String name, String program,
                                    String submitServer, String resultServer, String uuid, long start) {
        RequestStatus status = RequestStatus.UNKNOWN;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            Iterator<ResultBlock> it = stub.withDeadlineAfter(15, TimeUnit.SECONDS)
                    .getResult(RequestId.newBuilder().setId(uuid).build());
            while (it.hasNext()) {
                ResultBlock b = it.next();
                switch (b.getContentCase()) {
                    case STATUS -> status = b.getStatus();
                    case OUTPUT_CHUNK -> out.writeBytes(b.getOutputChunk().toByteArray());
                    case ERROR_CHUNK -> err.writeBytes(b.getErrorChunk().toByteArray());
                    default -> { }
                }
            }
        } catch (StatusRuntimeException e) {
            err.writeBytes(("gRPC error: " + e.getStatus().getCode() + " " + e.getStatus().getDescription()).getBytes());
        }
        return new Result(name, program, submitServer, resultServer, uuid, status,
                out.toString(), err.toString(), System.currentTimeMillis() - start);
    }

    private static synchronized void log(String msg) {
        System.out.println(msg);
    }

    private static void printSummary(List<Result> results) {
        System.out.println("\n==================== SUMMARY ====================");
        System.out.printf("%-5s %-10s %-22s %-22s %-9s %7s%n",
                "CLI", "PROGRAM", "SUBMITTED TO", "RESULT FROM", "STATUS", "TIME");
        for (Result r : results) {
            System.out.printf("%-5s %-10s %-22s %-22s %-9s %6.1fs%n",
                    r.client(), r.program(), r.submitServer(), r.resultServer(), r.status(), r.millis() / 1000.0);
        }
        System.out.println("\n==================== OUTPUTS ====================");
        for (Result r : results) {
            System.out.println("--- " + r.client() + " " + r.program() + " (" + r.status() + ") " + r.uuid());
            if (!r.output().isBlank()) System.out.println("stdout:\n" + lastLines(r.output().strip(), 3));
            if (!r.error().isBlank()) System.out.println("stderr:\n" + lastLines(r.error().strip(), 3));
        }
    }

    // Os tracebacks e o memory.py podem ser longos: mostra só o fim
    private static String lastLines(String s, int n) {
        String[] lines = s.split("\n");
        if (lines.length <= n) return s;
        return "...\n" + String.join("\n", List.of(lines).subList(lines.length - n, lines.length));
    }
}
