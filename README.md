# Jogo da Forca Cliente/Servidor

Projeto acadêmico completo em Java 17, usando TCP sockets. O servidor aceita vários clientes, mantém uma sala de espera, cria partidas independentes com exatamente dois jogadores e processa uma jogada por vez com `Semaphore(1)`.

O pacote possui interface gráfica tanto no modo solo quanto no modo online. Todas as jogadas passam pelo servidor, inclusive no modo solo: o bot funciona como o segundo cliente conectado por socket.

## Início rápido — modo gráfico

No Windows, dê dois cliques em:

```text
INICIAR_JOGO.bat
```

Também é possível executar pelo terminal aberto na pasta `forca-online`:

```cmd
java client\GameLauncher.java
```

Na tela inicial, informe seu nome e escolha **Solo contra bot**. O programa inicia automaticamente servidor principal, servidor reserva e bot. O humano e o bot entram na mesma sala como dois clientes distintos. A tela mostra os dois bonecos, palavra, letras usadas, mensagens e jogador da vez. Antes de abrir, encerre qualquer servidor antigo que ainda esteja usando a porta 5050.

O banco possui **156 palavras em 13 categorias**. A categoria escolhida é exibida acima da palavra. Quando um jogador acerta uma letra, seu perfil mostra temporariamente um emoji de provocação. O teclado usa desenho próprio para manter letras e botões visíveis mesmo com o tema escuro do Windows.

Durante seu turno, você também pode escrever a resposta completa no campo **Adivinhar palavra**. Uma resposta incorreta acrescenta um erro e passa o turno; uma resposta correta encerra a partida. Ao final, o botão **Jogar de novo** prepara uma revanche: no solo o bot aceita automaticamente e, no online, os dois jogadores precisam clicar.

Para dois jogadores reais, cada pessoa abre `Jogar_Online.bat`. O primeiro aguarda na sala e o segundo completa a partida. Em computadores diferentes, informe o IP virtual ou endereço do servidor.

Cada jogador tem seu próprio personagem detalhado em pixel art e contador de erros. Cabeça, rosto, tronco, braços, mãos, pernas, calçados e roupas aparecem progressivamente a cada erro. A cena ajusta automaticamente sua escala à altura disponível, evitando que pernas ou calçados sejam cortados em telas menores. Toda alteração é transmitida aos dois clientes da partida. O projeto também inclui replicação de estado, reconexão automática e uma infraestrutura com duas VMs e IP virtual para demonstrar failover.

## Requisitos atendidos

| Requisito | Implementação |
|---|---|
| Comunicação por socket | `ServerSocket` e `Socket` TCP |
| Servidor resiliente | duas VMs, Keepalived/VRRP, IP virtual e replicação do estado |
| Múltiplos jogadores | thread por conexão e `BlockingQueue` na sala de espera |
| Máximo de 2 por partida | cada `GameSession` recebe exatamente dois jogadores |
| Um jogador por vez | `Semaphore(1, true)` e validação do token do turno |
| Status para os dois clientes | mensagem `STATE` enviada simultaneamente aos dois |
| Um boneco por jogador | erros separados em `Player.errors` |
| Retomada após queda | token do jogador + snapshot replicado + reconexão automática |
| Interface gráfica | Swing, Java2D e teclado clicável em `GameLauncher.java` |
| Jogar sozinho | bot executado como segundo cliente TCP |

## Estrutura

```text
forca-online/
├── client/Client.java
├── client/GameLauncher.java
├── INICIAR_JOGO.bat
├── Jogar_Sozinho.bat
├── Jogar_Online.bat
├── server/Server.java
├── server/words.txt
├── docs/RELATORIO.md
├── docs/GUIA-VM.md
├── infra/Vagrantfile
├── infra/keepalived-primary.conf
├── infra/keepalived-backup.conf
├── scripts/
└── tests/integration_test.py
```

Não há Maven, Gradle ou bibliotecas externas. O Java 17 executa e compila os arquivos-fonte diretamente.

## Como o solo mantém os requisitos

```text
Cliente gráfico humano ─┐
                       ├── Socket TCP → Servidor → GameSession(2 jogadores)
Cliente bot ────────────┘                    │
                                      Semaphore(1)
                                             │
                                  estado enviado aos dois
```

O bot não altera a palavra ou o turno diretamente. Ele apenas recebe `STATE` e envia `GUESS`, exatamente como outro cliente. O servidor continua responsável por sala de espera, pareamento, semáforo, erros individuais e replicação para o reserva.

No início rápido, principal e reserva são processos locais para facilitar o teste. Para demonstrar especificamente o requisito de duas VMs e transferência de IP, utilize a configuração de `infra/` descrita em `docs/GUIA-VM.md`; o mesmo cliente gráfico pode se conectar ao IP virtual.

## Execução simples em um computador

Pré-requisitos: Java 17 ou superior e três terminais.

Terminal 1 — servidor:

```bash
java server/Server.java --role=primary --port=5050
```

Terminal 2 — primeiro jogador:

```bash
java client/Client.java --name=Luis --servers=127.0.0.1:5050
```

Terminal 3 — segundo jogador:

```bash
java client/Client.java --name=Joao --servers=127.0.0.1:5050
```

No Windows, os scripts equivalentes estão em `scripts\run-primary.bat` e `scripts\run-client.bat`.

## Demonstração local do failover

O script abaixo inicia o principal na porta 5050 e o reserva na 5052. A replicação usa a porta 5051:

```bash
bash scripts/run-local-demo.sh
```

Abra dois terminais de cliente:

```bash
java client/Client.java --name=Luis --servers=127.0.0.1:5050,127.0.0.1:5052
java client/Client.java --name=Joao --servers=127.0.0.1:5050,127.0.0.1:5052
```

Ao encerrar o servidor principal, os clientes tentam o segundo endereço, enviam seus tokens e recebem o último estado replicado.

## Demonstração com duas VMs

Com Vagrant e VirtualBox instalados:

```bash
cd infra
vagrant up
```

Endereços usados:

- VM principal: `192.168.56.11`
- VM reserva: `192.168.56.12`
- IP virtual acessado pelos clientes: `192.168.56.100`

Cliente:

```bash
java client/Client.java --name=Luis --servers=192.168.56.100:5050
```

Para simular a falha durante uma partida:

```bash
cd infra
vagrant halt -f primary
```

O Keepalived transfere o IP virtual para a VM reserva. A conexão TCP antiga cai — isso é inevitável —, mas o cliente reconecta no mesmo IP e recupera a partida usando seu token. Consulte `docs/GUIA-VM.md` para a apresentação passo a passo.

## Teste automatizado

Com Python 3 e Java 17:

```bash
python3 tests/integration_test.py
```

O teste abre servidores e sockets reais e valida:

- formação de duas partidas com quatro jogadores;
- turnos e estados iguais nos dois clientes;
- erros e bonecos individuais;
- replicação de estado;
- queda do principal;
- reconexão ao reserva e continuação da partida.

Resultado esperado:

```text
OK: lobby, semaforo, dois bonecos, replicacao e failover validados.
```

## Comandos do cliente

- digite uma letra e pressione Enter para jogar;
- `/sair` encerra o cliente;
- uma letra fora do turno é recusada pelo servidor;
- letras repetidas não consomem o turno.

## Observação de segurança

A porta de replicação usa uma chave compartilhada simples, adequada para a demonstração acadêmica. Em produção, a replicação deveria usar TLS, autenticação forte e armazenamento persistente.
