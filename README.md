# Jogo da Forca Distribuído — Cliente/Servidor

Projeto acadêmico em **Java 17** que implementa um jogo da forca cliente/servidor com sockets TCP, sala de espera, partidas de até dois jogadores, controle de turno no servidor, dois bonecos independentes, alta disponibilidade e persistência.

A versão final suporta duas formas de demonstrar resiliência:

- **Docker + HAProxy** — recomendada para a apresentação;
- **duas VMs + Keepalived/VRRP** — mantida para demonstrar a arquitetura pedida originalmente.

## Requisitos da atividade

| Requisito | Como foi implementado |
|---|---|
| Comunicação por socket | `ServerSocket`/`Socket` TCP |
| Servidor resiliente | `primary` + `backup`, replicação de snapshots e reconexão por token |
| Substituição automática do servidor | Docker: HAProxy; VMs: Keepalived + IP virtual |
| Múltiplas requisições | uma tarefa por cliente + `BlockingQueue<Player>` |
| Sala de espera | jogadores aguardam na fila até formar um par |
| Máximo 2 jogadores por partida | cada `GameSession` possui exatamente dois `Player` |
| Um jogador por vez | `Semaphore(1, true)` + validação do jogador da vez |
| Status enviado aos clientes | mensagem `STATE` transmitida aos dois jogadores |
| Boneco separado por jogador | cada `Player` possui seu próprio contador `errors` |
| Ambos veem ambos os bonecos | cada `STATE` carrega nome e erros dos dois jogadores |
| Banco de dados | SQLite persistente por serviço HTTP no modo Docker |
| Retomada de estado | replicação entre servidores + snapshots ativos no banco |
| Proteção contra split brain | backup só promove após perder heartbeats do primário; no modo VM clientes usam apenas o IP virtual |

## Arquitetura Docker

```text
Cliente 1 ─┐
Cliente 2 ─┼── TCP :5050 ──> HAProxy ──> Servidor PRIMARY
Cliente N ─┘                    │              │
                                │              ├── snapshots ──> Servidor BACKUP
                                │              │
                                └─ failover ───┘
                                               │
                                      HTTP snapshots
                                               │
                                        SQLite / volume
```

Os clientes usam **um único endereço: `127.0.0.1:5050`**. Se o servidor principal cair, a conexão TCP antiga termina; o cliente reconecta automaticamente no mesmo endereço e o HAProxy passa a nova conexão ao servidor reserva.

## Início rápido — Docker

Pré-requisitos: Docker e Java 17+.

```bash
docker compose up -d --build
```

Depois abra `Jogar_Online.bat` duas vezes e mantenha:

```text
Servidor: 127.0.0.1:5050
```

No Linux/macOS, também é possível abrir o cliente com:

```bash
java client/GameLauncher.java
```

Para forçar a queda do primário durante uma partida:

```bash
docker compose stop primary
```

A partida deve reconectar e continuar pelo reserva.

Guia completo: `docs/GUIA-DOCKER.md`.

## Interface gráfica

`client/GameLauncher.java` é a interface Swing. Ela mostra:

- categoria e palavra mascarada;
- teclado clicável;
- tentativa da palavra inteira;
- letras já utilizadas;
- jogador da vez;
- cronômetro do turno visível para os dois jogadores (`SEU TEMPO` / `TEMPO DO ADVERSÁRIO`);
- status de conexão/reconexão;
- dois painéis de jogador;
- um boneco independente para cada jogador;
- revanche ao fim da partida.

A interface não contém as regras da partida. Ela recebe `STATE` e apenas renderiza o estado decidido pelo servidor. A identidade visual usa os **tokens** de `p1` e `p2`, então dois jogadores podem ter o mesmo nome sem ambos aparecerem como “VOCÊ”.

## Sala de espera e múltiplas partidas

O servidor aceita conexões continuamente e usa uma `BlockingQueue<Player>`. O emparelhador retira dois jogadores conectados e cria uma `GameSession` exclusiva.

Com quatro clientes:

```text
Jogador A + Jogador B -> Partida 1
Jogador C + Jogador D -> Partida 2
```

Não existe limite global de duas conexões; o limite de dois é **por partida**.

## Semáforo e turno

Cada `GameSession` possui:

```java
new Semaphore(1, true)
```

Toda jogada entra na região crítica antes de alterar letras, erros, vencedor ou turno. Além disso, o servidor compara o jogador que enviou a requisição com `players[turnIndex]`. Portanto, mesmo que duas threads façam requisições, o estado é modificado de forma serializada e uma tentativa fora do turno é recusada.

## Estado e dois bonecos

Após uma jogada válida, o servidor envia `STATE` aos dois jogadores. A mensagem contém, entre outros campos:

- partida;
- palavra mascarada;
- nome do jogador 1 e seus erros;
- nome do jogador 2 e seus erros;
- token do jogador da vez;
- letras usadas;
- status/vencedor;
- versão do estado;
- categoria.

Se apenas o Jogador 1 errar, somente `p1.errors` aumenta. Mesmo assim, o novo estado completo é transmitido aos dois clientes, então ambos enxergam o boneco atualizado do Jogador 1 e o boneco inalterado do Jogador 2.

## Persistência SQLite

O serviço `database/db_service.py` usa o `sqlite3` da biblioteca padrão do Python e mantém:

- tabela `matches`: último snapshot de cada partida;
- tabela `snapshot_history`: histórico de versões recebidas.

No Docker, os dados ficam no volume `forca_db`.

Endpoints úteis para demonstração:

```text
GET http://localhost:8080/health
GET http://localhost:8080/stats
GET http://localhost:8080/matches
```

O Java recebe a URL pelo argumento:

```text
--db-url=http://database:8080
```

Sem `--db-url`, o jogo continua funcionando normalmente sem persistência externa.

## Resiliência

### Replicação

O servidor ativo envia snapshots da partida para o outro nó. O snapshot contém palavra, categoria, tokens, nomes, erros, letras usadas, turno, resultado, versão, início do turno e pedidos de revanche pendentes. No modo VM a replicação é bidirecional; isso permite que o nó principal que retorna receba o estado mais novo antes de um eventual failback.

### Reconexão

Ao entrar pela primeira vez, o cliente recebe um token. Em uma queda de conexão, ele tenta novamente e envia:

```text
HELLO|nome|token
```

Se o token existir no servidor que assumiu, o jogador volta para sua `GameSession`.

### Docker

O HAProxy monitora `primary:5050` e `backup:5050`. O reserva recebe **heartbeats** do primário e rejeita clientes enquanto esses heartbeats estão recentes. Depois da queda, ele só se promove quando o prazo de promoção expira; assim uma conexão direta/acidental no reserva não cria uma segunda autoridade. Depois de um failover, o `primary` não reinicia automaticamente; o retorno é feito pelo script de failback controlado, evitando dois servidores ativos sobre a mesma partida.

### VMs

A opção original com duas VMs permanece em `infra/`. O Keepalived transfere `192.168.56.100` da VM principal para a reserva. A porta 5050 dos IPs reais das VMs é bloqueada no provisionamento, obrigando os clientes a usarem o VIP. A VM principal usa `preempt_delay` e recebe replicação reversa para ter tempo de sincronizar o estado antes de recuperar o VIP.

## Banco de palavras

`server/words.txt` contém 156 palavras em 13 categorias, no formato:

```text
CATEGORIA|PALAVRA
```

A seleção é feita no servidor.

## Protocolo principal

| Direção | Mensagem | Uso |
|---|---|---|
| cliente -> servidor | `HELLO|nomeBase64|token` | entrar/reconectar |
| servidor -> cliente | `WELCOME|token|NEW/RECONNECTED` | identidade |
| servidor -> cliente | `WAITING|...` | sala de espera |
| cliente -> servidor | `GUESS|letraBase64` | jogar letra |
| cliente -> servidor | `WORD|palavraBase64` | tentar palavra inteira |
| cliente -> servidor | `REPLAY` | pedir revanche |
| cliente -> servidor | `QUIT` | abandonar |
| servidor -> ambos | `STATE|...` | estado completo |
| primary -> backup | `SYNC|segredo|snapshot` | replicação |

## W.O. e timeouts

- `QUIT`: derrota imediata por W.O.;
- desconexão: existe janela de reconexão configurada por `--grace`;
- jogador parado na sua vez: `--turn-timeout` encerra por W.O.;
- o padrão é **120 segundos por turno**; o servidor envia o tempo restante no `STATE` e os dois clientes exibem a mesma contagem regressiva;
- a reconexão/failover não reinicia o cronômetro do turno;
- o próprio `guess`/`guessWord` valida o deadline antes de aceitar a jogada, portanto uma requisição que chega após `00:00` perde por W.O. mesmo antes do próximo ciclo do watchdog;
- `--turn-timeout=0` desativa o limite de turno e a interface mostra `SEM LIMITE`.

## Testes automatizados

Pré-requisitos: Python 3 e Java 17+.

```bash
python tests/integration_test.py
python tests/walkover_test.py
```

O teste de integração valida com sockets reais:

- quatro jogadores e duas partidas;
- máximo de dois jogadores por partida;
- recusa de jogada fora do turno;
- estado idêntico nos dois clientes;
- erros/bonecos independentes;
- persistência SQLite;
- replicação para o reserva;
- queda do primário;
- continuação no reserva;
- restauração de partida após reiniciar todos os servidores usando o banco.

`walkover_test.py` valida timeout, cronômetro e saída voluntária.

`resilience_test.py` valida os casos de regressão mais críticos: backup em standby recusando cliente enquanto o primário vive, jogadores com nomes iguais identificados por token, cronômetro preservado no failover, jogada depois do deadline recusada com W.O. e pedido de revanche preservado através do failover.

Também existe um autoteste do cliente gráfico/bot:

```bash
java client/GameLauncher.java --network-check
```

## Estrutura final

```text
forca-online-completo/
├── client/
│   ├── Client.java
│   └── GameLauncher.java
├── server/
│   ├── Server.java
│   └── words.txt
├── database/
│   ├── db_service.py
│   └── schema.sql
├── docker/
│   ├── Dockerfile.server
│   ├── Dockerfile.database
│   └── haproxy.cfg
├── docs/
│   ├── GUIA-DOCKER.md
│   ├── GUIA-VM.md
│   └── RELATORIO.md
├── infra/
│   ├── Vagrantfile
│   ├── keepalived-primary.conf
│   └── keepalived-backup.conf
├── scripts/
├── tests/
│   ├── integration_test.py
│   └── walkover_test.py
├── docker-compose.yml
├── INICIAR_JOGO.bat
├── Jogar_Online.bat
└── Jogar_Sozinho.bat
```

## Execução sem Docker

Servidor principal:

```bash
java server/Server.java --role=primary --port=5050
```

Dois clientes de terminal:

```bash
java client/Client.java --name=Ana --servers=127.0.0.1:5050
java client/Client.java --name=Bruno --servers=127.0.0.1:5050
```

Para simular dois processos localmente com failover por lista de endereços:

```bash
bash scripts/run-local-demo.sh
```

## Observações técnicas

A replicação entre principal e reserva é assíncrona. Portanto, uma falha exatamente antes da entrega do último snapshot pode perder a jogada mais recente. Para um trabalho acadêmico isso é uma limitação aceitável e documentada; em produção seria indicado usar consenso/log durável, autenticação forte/TLS e um banco altamente disponível.
