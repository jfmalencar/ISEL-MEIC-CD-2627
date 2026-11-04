package grpcrunserverapp;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.google.protobuf.ByteString;
import contracts.execution.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import redis.clients.jedis.RedisClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ExecutionService extends ExecutionGrpc.ExecutionImplBase {
    private volatile RedisClient redis;
    private final String myAddress;
    private final DockerClient docker;
    private final ExecutorService executor;
    private final Path baseDir;
    public static final String IMAGE = "python";
    public static final String TAG = "3.12-slim";
    private static final long MEMORY = 256L * 1024 * 1024;
    private static final String COMMAND =
            "timeout 10 python /work/prog.py < /work/input.txt > /work/out.txt 2> /work/err.txt;"
                    + " echo $? > /work/exit.txt";

    public ExecutionService(String myAddress, DockerClient docker, ExecutorService executor, Path baseDir ) {
        this.myAddress = myAddress;
        this.docker = docker;
        this.executor = executor;
        this.baseDir = baseDir;
    }

    public void setRedis(RedisClient redis) {
        this.redis = redis;
    }
    private RedisClient redis() {
        RedisClient r = redis;
        if (r == null) {
            throw Status.UNAVAILABLE.withDescription("RunServer not ready yet").asRuntimeException();
        }
        return r;
    }

    @Override
    public StreamObserver<FileBlock> submit(StreamObserver<RequestId> responseObserver) {
        return new StreamObserver<>() {
            final ByteArrayOutputStream program = new ByteArrayOutputStream();
            final ByteArrayOutputStream input = new ByteArrayOutputStream();

            @Override
            public void onNext(FileBlock value) {
                switch (value.getContentCase()) {
                    case PROGRAM_CHUNK -> program.writeBytes(value.getProgramChunk().toByteArray());
                    case INPUT_CHUNK ->  input.writeBytes(value.getInputChunk().toByteArray());
                    default -> System.out.println("[Execution] empty block received");
                }
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("Error on submit: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                if (program.size() == 0) {
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("empty program file").asRuntimeException());
                    return;
                }
                String uuid = UUID.randomUUID().toString();
                Path dir = baseDir.resolve(uuid);
                try {
                    Files.createDirectories(dir);
                    Files.write(dir.resolve("prog.py"),  program.toByteArray());
                    Files.write(dir.resolve("input.txt"),  input.toByteArray());
                } catch (IOException e) {
                    deleteDir(dir);
                    responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                    return;
                }
                try {
                    RedisClient r = redis();
                    r.hset(key(uuid), Map.of("status", RequestStatus.PENDING.name(), "server", myAddress));
                }catch (RuntimeException e) {
                    deleteDir(dir);
                    responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
                    return;
                }
                responseObserver.onNext(RequestId.newBuilder().setId(uuid).build());
                responseObserver.onCompleted();

                executor.submit(() -> runContainer(uuid, dir));




            }

        };

    }

    @Override
    public void getResult(RequestId request, StreamObserver<ResultBlock> responseObserver) {
        String uuid = request.getId();
        try {
            Map<String,String> info = redis().hgetAll(key(uuid));
            if (info.isEmpty()) {
                sendStatusOnly(responseObserver, RequestStatus.UNKNOWN);
                return;
            }
            String status = info.get("status");
            String server = info.get("server");

            if (!server.equals(myAddress)) {
                forwardGetResult(server, request, responseObserver);
                return;
            }

            if (status.equals(RequestStatus.PENDING.name())) {
                sendStatusOnly(responseObserver, RequestStatus.PENDING);
                return;
            }

            // garante que so um run server vai entregar este resultado
            long removed = redis().del(key(uuid));
            if (removed == 0) {
                sendStatusOnly(responseObserver, RequestStatus.UNKNOWN);
                return;
            }

            Path dir = baseDir.resolve(uuid);
            try {
                responseObserver.onNext(ResultBlock.newBuilder()
                        .setStatus(RequestStatus.valueOf(status))
                        .build());

                sendFile(dir.resolve("out.txt"), responseObserver, false);
                sendFile(dir.resolve("err.txt"), responseObserver, true);

                responseObserver.onCompleted();
            }finally {// se o sendFile falhar elimina os ficheiros na mesma
                deleteDir(dir);
            }

        } catch (IOException e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }catch(StatusRuntimeException e) {
            responseObserver.onError(e);
        }catch (RuntimeException e)  {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }




    }

    private void forwardGetResult(String server, RequestId request, StreamObserver<ResultBlock> responseObserver) {
        String[] address = server.split(":");
        String host = address[0];
        int port = Integer.parseInt(address[1]);

        ManagedChannel runServerChannel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        try{
            Iterator<ResultBlock> it = ExecutionGrpc.newBlockingStub(runServerChannel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getResult(request);

            while (it.hasNext()) {
                responseObserver.onNext(it.next());
            }
            responseObserver.onCompleted();
        }finally {
            runServerChannel.shutdown();
        }

    }

    private static void sendStatusOnly(StreamObserver<ResultBlock> obs, RequestStatus status) {
        obs.onNext(ResultBlock.newBuilder().setStatus(status).build());
        obs.onCompleted();
    }

    private void sendFile(Path file, StreamObserver<ResultBlock> responseObserver, boolean isError) throws IOException {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                ByteString chunk = ByteString.copyFrom(buffer, 0, n);
                ResultBlock.Builder builder = ResultBlock.newBuilder();
                if (isError) {
                    builder.setErrorChunk(chunk);
                }else {
                    builder.setOutputChunk(chunk);
                }
                responseObserver.onNext(builder.build());
            }
        }

    }



    private void runContainer(String uuid, Path dir) {
        try {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode("none")
                    .withMemory(MEMORY)
                    .withMemorySwap(MEMORY)
                    .withNanoCPUs(1_000_000_000L) // limitar o cpu (1 cpu por contentor)
                    .withBinds(new Bind(dir.toString(), new Volume("/work")));
            CreateContainerCmd create = docker.createContainerCmd(IMAGE + ":" + TAG)
                    .withName("run-" + uuid)
                    .withHostConfig(hostConfig)
                    .withCmd("sh", "-c", COMMAND);

            String user = currentUser();
            if (user != null) {
                create.withUser(user);
            }

            String containerId = create.exec().getId();
            try {
                docker.startContainerCmd(containerId).exec();
                docker.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitStatusCode();
            } finally {
                docker.removeContainerCmd(containerId).withForce(true).exec();
            }

            int exitCode = readExitCode(dir.resolve("exit.txt"));
            RequestStatus status = switch (exitCode) {
                case 0 -> RequestStatus.COMPLETE;
                case 124 -> RequestStatus.TIMEOUT;
                default -> RequestStatus.ERROR;
            };


            RedisClient r = redis();
            r.hset(key(uuid), Map.of("status", status.name()));

        }catch (Exception e) { // garante que o estado do pedido não fica pending em caso de erro
            System.out.println("[run " + uuid + "] failed: " + e.getMessage());
            try {
                redis().hset(key(uuid), Map.of("status", RequestStatus.ERROR.name()));
            } catch (Exception ignored) {
                // se o redis também estiver em baico não ha mais nada a fazer...
            }
        }



    }

    private static int readExitCode(Path file) throws IOException {
        return Files.exists(file) ? Integer.parseInt(Files.readString(file).strip()) : -1;
    }

    private static String currentUser() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return null;
        }
        com.sun.security.auth.module.UnixSystem unix = new com.sun.security.auth.module.UnixSystem();
        return unix.getUid() + ":" + unix.getGid();
    }

    private static void deleteDir(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())   // ficheiros antes da pasta
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static String key(String uuid) {
        return "request:" + uuid;
    }
}
