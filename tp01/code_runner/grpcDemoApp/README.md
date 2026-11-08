# grpcDemoApp — Demo.jar

Simula vários clientes concorrentes do CodeRunner. Cada cliente corre numa thread e:

1. pede um RunServer ao ManagerServer (contrato **b**) e submete um programa Python (contrato **c**);
2. volta a pedir um RunServer ao Manager, que pode ser outro, e faz polling do resultado nesse servidor
   (mostra que o resultado se obtém a partir de qualquer RunServer);
3. repete o pedido de resultado a cada 1,5 s enquanto o estado for `PENDING` (desiste ao fim de 120 s).

No fim imprime uma tabela-resumo (servidor onde submeteu, servidor de onde leu, estado, tempo) e o stdout/stderr de cada pedido.

## Comando

```
java -jar build/libs/Demo.jar <managerIP> <managerPort> <pastaProgramas> <copiasPorPrograma>
```

| # | Argumento           | Significado                                         | Por omissão     |
|---|---------------------|-----------------------------------------------------|-----------------|
| 1 | `managerIP`         | IP do ManagerServer                                 | `localhost`     |
| 2 | `managerPort`       | Porto do ManagerServer                              | `8000`          |
| 3 | `pastaProgramas`    | Pasta com os programas da demo (ver abaixo)         | `demo-programs` |
| 4 | `copiasPorPrograma` | Cópias de cada programa: nº de clientes = 4 × cópias | `3`             |

São precisos **os 4 argumentos ou nenhum**. Com outro número de argumentos são usados os valores por omissão.

### Exemplos

A partir da pasta `code_runner/` (onde está `demo-programs/`):

```
# local
java -jar grpcDemoApp/build/libs/Demo.jar localhost 8000 demo-programs 2

# Google Cloud (IP externo da VM do Manager)
java -jar grpcDemoApp/build/libs/Demo.jar <IP_DO_MANAGER> 8000 demo-programs 3
```

Com 3 cópias são lançados 12 clientes ao mesmo tempo. Como cada RunServer executa no máximo 5 containers em simultâneo,
alguns pedidos ficam `PENDING` em fila, o que demonstra o limite de containers.

## Gerar o jar

```
cd grpcDemoApp
./gradlew shadowJar        # gera build/libs/Demo.jar
```

## Programas da demo (`code_runner/demo-programs/`)

| Ficheiro                | Caso do enunciado    | Estado esperado                      |
|-------------------------|----------------------|--------------------------------------|
| `prog.py` + `input.txt` | termina normalmente  | `COMPLETE` (`count: 4`, `sum: 55.5`) |
| `erro.py`               | termina com erro     | `ERROR` (traceback `ZeroDivisionError`) |
| `memory.py`             | termina com erro     | `ERROR` (passa os 256 MB, `Killed`)  |
| `loop.py`               | não termina          | `TIMEOUT` (morto ao fim de 10 s)     |

Se faltar algum ficheiro na pasta, esse programa é ignorado e os restantes correm na mesma.

## Pré-requisitos

Antes de correr o Demo têm de estar a correr, por esta ordem:

1. Redis
2. ManagerServer
3. pelo menos um RunServer registado no Manager
