# CodeRunner — TPA1 Computação Distribuída

Plataforma que executa programas Python submetidos por clientes. Cada execução corre num container Docker
efémero e isolado. Vários **RunServers** repartem a carga e um **ManagerServer** distribui os clientes por eles.
O estado dos pedidos fica num **Redis** partilhado.

## 1. Arquitetura

| Projeto / pasta     | Papel                                                                 | Classe principal                 |
|---------------------|-----------------------------------------------------------------------|----------------------------------|
| `grpcContract`      | Contratos protobuf (`.proto`) e stubs Java gerados                     | —                                |
| `grpcServerApp`     | **ManagerServer**: registo de RunServers e atribuição de servidor      | `grpcserverapp.ManagerServer`    |
| `grpcRunServerApp`  | **RunServer**: recebe programas, executa-os em containers, devolve resultados | `grpcrunserverapp.GrpcRunServer` |
| `grpcClientApp`     | **Client** interativo (menu)                                           | `grpcclientapp.Client`           |
| `grpcDemoApp`       | **Demo.jar**: vários clientes concorrentes ([README](grpcDemoApp/README.md)) | `grpcdemoapp.Demo`               |
| `demo-programs/`    | Programas Python usados pelo Demo                                      | —                                |

### Contratos (separados — *loose coupling*)

| Contrato | Entre                 | Ficheiro          | Operações                                                                 |
|----------|-----------------------|-------------------|---------------------------------------------------------------------------|
| **a**    | RunServer → Manager   | `Register.proto`  | `register(ip, porto)` → endereço do Redis                                 |
| **b**    | Client → Manager      | `Discovery.proto` | `getServer()` → IP e porto de um RunServer                                |
| **c**    | Client → RunServer    | `Execution.proto` | `submit(stream FileBlock)` → id do pedido; `getResult(id)` → `stream ResultBlock` (estado + stdout + stderr) |

### Estado no Redis

| Chave            | Tipo | Conteúdo                                                             |
|------------------|------|----------------------------------------------------------------------|
| `runservers`     | set  | `ip:porto` de cada RunServer registado                               |
| `request:<uuid>` | hash | `status` (`PENDING`, `COMPLETE`, `TIMEOUT`, `ERROR`) e `server` (`ip:porto` do RunServer que executa o pedido) |

O stdout e o stderr **não** vão para o Redis: ficam em `requests/<uuid>/out.txt` e `err.txt` no RunServer que executou o pedido.

## 2. Pressupostos e decisões

- **O ManagerServer nunca falha.** Falhas de RunServers não são tratadas: um RunServer que caia continua no set `runservers`.
  Podem ser adicionados RunServers a qualquer momento.
- **Balanceamento:** o Manager atribui o RunServer com menos pedidos `PENDING` no Redis. Em caso de empate, roda entre os servidores empatados.
- **Isolamento de cada container:** sem rede (`--network none`), 256 MB de memória sem swap, 1 CPU, `timeout 10` segundos,
  executado com o uid/gid do utilizador que lança o RunServer (sem root). O container é sempre removido (`finally`), mesmo em caso de erro.
- **Fila:** cada RunServer executa no máximo **5 containers em simultâneo**. Os restantes pedidos ficam em fila com estado `PENDING`.
- **Estados:** o código de saída em `exit.txt` define o estado: `0` → `COMPLETE`, `124` → `TIMEOUT`, outro → `ERROR`
  (ex.: `137` quando excede a memória). Um id inexistente devolve `UNKNOWN`.
- **O `submit` não espera pela execução:** regista o pedido como `PENDING` no Redis, devolve logo o UUID e executa o container em background.
- **O resultado pode ser pedido a qualquer RunServer:** se o pedido foi executado noutro servidor, o RunServer reencaminha o `getResult`
  para esse servidor (endereço guardado no campo `server` do Redis). Funciona também com uma nova instância do cliente.
- **O resultado só se lê uma vez:** depois de entregue, o pedido é apagado do Redis e a pasta `requests/<uuid>/` é apagada do disco.
  Uma segunda leitura devolve `UNKNOWN`.
- **Limitação:** um resultado que nunca seja lido fica no disco do RunServer.

## 3. Pré-requisitos

- **JDK 21** ou superior e **Docker** em todas as máquinas que correm servidores.
- Em cada VM de RunServer, obter a imagem antes da demonstração:
  ```
  docker pull python:3.12-slim
  ```
- O utilizador que lança o RunServer tem de poder usar o Docker sem `sudo` (no Linux: `sudo usermod -aG docker $USER` e voltar a entrar na sessão).

## 4. Compilar

Em cada projeto (`grpcServerApp`, `grpcRunServerApp`, `grpcClientApp`, `grpcDemoApp`):

```
./gradlew shadowJar
```

Se der `permission denied` (o zip pode perder as permissões), usar `sh gradlew shadowJar`.
O contrato é compilado automaticamente (build composto com `includeBuild("../grpcContract")`): as pastas têm de estar lado a lado.

| Projeto            | Jar gerado                                                   |
|--------------------|--------------------------------------------------------------|
| `grpcServerApp`    | `build/libs/grpcServerApp-1.0-jar-with-dependencies.jar`     |
| `grpcRunServerApp` | `build/libs/grpcRunServerApp-1.0-jar-with-dependencies.jar`  |
| `grpcClientApp`    | `build/libs/grpcClientApp-1.0-jar-with-dependencies.jar`     |
| `grpcDemoApp`      | `build/libs/Demo.jar`                                        |

## 5. Executar (por esta ordem)

### 5.1 Redis (na máquina do Manager)

```
docker run -d --name redis -p 6379:6379 redis
```

Não usar `-p 127.0.0.1:6379:6379` (como no script de exemplo): assim a porta só aceita ligações da própria máquina
e os RunServers de outras VMs não chegam ao Redis. Para voltar a arrancar mais tarde: `docker start redis`.

### 5.2 ManagerServer

```
java -jar grpcServerApp/build/libs/grpcServerApp-1.0-jar-with-dependencies.jar <porto> <IP_Redis_para_RunServers>
```

| # | Argumento                   | Significado                                                                      | Por omissão |
|---|-----------------------------|----------------------------------------------------------------------------------|-------------|
| 1 | `porto`                     | Porto do ManagerServer                                                           | `8000`      |
| 2 | `IP_Redis_para_RunServers`  | IP da máquina do Manager/Redis **visto pelos RunServers** (enviado no registo)   | `127.0.0.1` |

Ambos opcionais. O próprio Manager liga-se sempre ao Redis em `localhost:6379`. No arranque imprime o endereço que vai anunciar:
`Redis address announced to RunServers: ...`.

### 5.3 RunServer

```
java -jar grpcRunServerApp/build/libs/grpcRunServerApp-1.0-jar-with-dependencies.jar <IP_RunServer> <porto> <IP_Manager> <porto_Manager>
```

| # | Argumento       | Significado                                                                  | Por omissão |
|---|-----------------|------------------------------------------------------------------------------|-------------|
| 1 | `IP_RunServer`  | IP com que o RunServer se regista — é o IP que os **clientes** vão receber   | `localhost` |
| 2 | `porto`         | Porto do RunServer                                                           | `8001`      |
| 3 | `IP_Manager`    | IP do ManagerServer                                                          | `localhost` |
| 4 | `porto_Manager` | Porto do ManagerServer                                                       | `8000`      |

São precisos **os 4 argumentos ou nenhum**. O RunServer cria a pasta `requests/` na diretoria onde é lançado:
**dois RunServers na mesma máquina devem usar portos diferentes e ser lançados em diretorias diferentes.**

### 5.4 Client (interativo)

```
java -jar grpcClientApp/build/libs/grpcClientApp-1.0-jar-with-dependencies.jar <IP_Manager> <porto_Manager>
```

| # | Argumento       | Significado            | Por omissão |
|---|-----------------|------------------------|-------------|
| 1 | `IP_Manager`    | IP do ManagerServer    | `localhost` |
| 2 | `porto_Manager` | Porto do ManagerServer | `8000`      |

O cliente pede um RunServer ao Manager e mostra um menu:
`1` submeter programa (caminho do `.py` e, opcionalmente, do ficheiro de input — Enter para nenhum) e recebe o id do pedido;
`2` obter resultado a partir de um id; `99` sair.

### 5.5 Demo

```
java -jar grpcDemoApp/build/libs/Demo.jar <IP_Manager> <porto_Manager> <pastaProgramas> <copiasPorPrograma>
```

Detalhes em [grpcDemoApp/README.md](grpcDemoApp/README.md).

## 6. Exemplos

### 6.1 Local (tudo na mesma máquina)

A partir da pasta `code_runner/`, cada comando num terminal diferente:

```
docker run -d --name redis -p 6379:6379 redis
java -jar grpcServerApp/build/libs/grpcServerApp-1.0-jar-with-dependencies.jar 8000

mkdir -p run1 && cd run1 && java -jar ../grpcRunServerApp/build/libs/grpcRunServerApp-1.0-jar-with-dependencies.jar localhost 8001 localhost 8000
mkdir -p run2 && cd run2 && java -jar ../grpcRunServerApp/build/libs/grpcRunServerApp-1.0-jar-with-dependencies.jar localhost 8002 localhost 8000

java -jar grpcClientApp/build/libs/grpcClientApp-1.0-jar-with-dependencies.jar localhost 8000
java -jar grpcDemoApp/build/libs/Demo.jar localhost 8000 demo-programs 2
```

### 6.2 Google Cloud (3 RunServers em 2 VMs)

Cada VM tem um **IP interno** (ex.: `10.128.0.x`, só visível entre VMs do projeto) e um **IP externo** (visível da internet).
A firewall da rede `default` já permite todo o tráfego entre VMs pelos IPs internos (regra `default-allow-internal`).

Exemplo com:
- VM Manager — interno `10.128.0.2`;
- VM1 — interno `10.128.0.3`, externo `<EXT_VM1>`;
- VM2 — interno `10.128.0.4`, externo `<EXT_VM2>`.

**Opção A — clientes a correr nas VMs (mais simples, tudo por IPs internos, sem firewall):**

```
# VM Manager
docker run -d --name redis -p 6379:6379 redis
java -jar grpcServerApp-1.0-jar-with-dependencies.jar 8000 10.128.0.2

# VM1 (dois RunServers, em diretorias diferentes)
cd ~/run1 && java -jar grpcRunServerApp-1.0-jar-with-dependencies.jar 10.128.0.3 8001 10.128.0.2 8000
cd ~/run2 && java -jar grpcRunServerApp-1.0-jar-with-dependencies.jar 10.128.0.3 8002 10.128.0.2 8000

# VM2
cd ~/run1 && java -jar grpcRunServerApp-1.0-jar-with-dependencies.jar 10.128.0.4 8001 10.128.0.2 8000

# Clientes / Demo (em qualquer VM)
java -jar grpcClientApp-1.0-jar-with-dependencies.jar 10.128.0.2 8000
java -jar Demo.jar 10.128.0.2 8000 demo-programs 3
```

**Opção B — clientes no PC dos alunos:**
os RunServers têm de se registar com o **IP externo** (é esse que o Manager entrega aos clientes) e é preciso criar uma regra de firewall
(*VPC network → Firewall → Create firewall rule*) a permitir **TCP 8000, 8001, 8002** de origem `0.0.0.0/0`.
O Redis continua a usar o IP interno (a porta 6379 não precisa de ficar aberta para a internet).

```
# VM Manager
java -jar grpcServerApp-1.0-jar-with-dependencies.jar 8000 10.128.0.2

# VM1 / VM2: 1.º argumento = IP externo da própria VM
java -jar grpcRunServerApp-1.0-jar-with-dependencies.jar <EXT_VM1> 8001 10.128.0.2 8000
java -jar grpcRunServerApp-1.0-jar-with-dependencies.jar <EXT_VM1> 8002 10.128.0.2 8000
java -jar grpcRunServerApp-1.0-jar-with-dependencies.jar <EXT_VM2> 8001 10.128.0.2 8000

# PC: IP externo da VM Manager
java -jar Demo.jar <EXT_MANAGER> 8000 demo-programs 3
```

| Argumento                         | Opção A (clientes nas VMs) | Opção B (clientes no PC) |
|-----------------------------------|----------------------------|--------------------------|
| Manager — `IP_Redis_para_RunServers` | interno do Manager       | interno do Manager       |
| RunServer — `IP_RunServer`        | interno da própria VM      | **externo** da própria VM |
| RunServer — `IP_Manager`          | interno do Manager         | interno do Manager       |
| Client/Demo — `IP_Manager`        | interno do Manager         | **externo** do Manager   |
| Regra de firewall                 | não é precisa              | TCP 8000, 8001, 8002     |

## 7. Dicas

- Se o Manager for reiniciado, o set `runservers` no Redis mantém os registos antigos (incluindo RunServers que já não existem).
  Para limpar: `docker exec redis redis-cli DEL runservers`, ou recriar o container do Redis.
- Ver o estado no Redis: `docker exec -it redis redis-cli` e depois `SMEMBERS runservers` ou `KEYS request:*`.
- Confirmar que não ficaram containers de pedidos: `docker ps -a --filter name=run-`.
