# Jogo da Forca Distribuído — Entrega Limpa

Projeto acadêmico em **Java 17** com arquitetura cliente/servidor usando **sockets TCP**.

## Requisitos atendidos

- comunicação cliente/servidor por `Socket` e `ServerSocket`;
- vários clientes conectados e sala de espera;
- exatamente 2 jogadores por partida;
- servidor controla o turno com `Semaphore(1, true)` e validação do jogador da vez;
- cada jogador possui seu próprio contador de erros/boneco;
- os dois clientes recebem o mesmo `STATE` e visualizam os dois jogadores;
- cronômetro do turno controlado pelo servidor e exibido nas duas telas;
- servidor principal + reserva, replicação de estado e reconexão por token;
- demonstração de failover com Docker/HAProxy e alternativa com VMs/Keepalived;
- persistência SQLite no modo Docker;
- testes de integração, W.O., timeout, concorrência e resiliência.

## Abrir o jogo

Pré-requisito: **Java 17 ou superior**.

No Windows, execute:

```text
INICIAR_JOGO.bat
```

Na tela inicial escolha **Solo contra bot** ou **Jogar Online**.

## Demonstração recomendada com Docker

Pré-requisitos: Docker Desktop e Java 17+.

1. Execute `INICIAR_DOCKER.bat`.
2. Execute `INICIAR_JOGO.bat` em dois computadores/janelas e escolha **Jogar Online**.
3. Use `127.0.0.1:5050` se os clientes estiverem na mesma máquina do Docker.
4. Para simular a falha do servidor principal, execute `FALHAR_SERVIDOR_PRIMARIO.bat`.
5. Os clientes devem reconectar e continuar pelo servidor reserva.
6. Para recuperar o principal de forma controlada, execute `RESTAURAR_SERVIDOR_PRIMARIO.bat`.
7. Ao terminar, execute `PARAR_DOCKER.bat`.

Mais detalhes: `docs/GUIA-DOCKER.md`.

## Testes

No terminal aberto na raiz do projeto:

```bash
python tests/integration_test.py
python tests/walkover_test.py
python tests/resilience_test.py
java client/GameLauncher.java --network-check
```

## Estrutura

```text
Jogo-da-Forca-ENTREGA/
├── INICIAR_JOGO.bat
├── INICIAR_DOCKER.bat
├── PARAR_DOCKER.bat
├── FALHAR_SERVIDOR_PRIMARIO.bat
├── RESTAURAR_SERVIDOR_PRIMARIO.bat
├── docker-compose.yml
├── README.md
├── client/
│   ├── Client.java
│   ├── GameLauncher.java
│   └── assets/hangman_sprite.png
├── server/
│   ├── Server.java
│   └── words.txt
├── database/
│   ├── db_service.py
│   └── schema.sql
├── docker/
├── infra/
├── scripts/
├── tests/
└── docs/
```

## O que não deve ser enviado junto

Não é necessário adicionar `.idea/`, `out/`, `*.iml`, `*.class`, `__pycache__/`, banco SQLite gerado em execução ou outras versões ZIP do projeto.

## Documentação

- `docs/RELATORIO.md`: explicação técnica e arquitetura;
- `docs/GUIA-DOCKER.md`: apresentação de failover com Docker;
- `docs/GUIA-VM.md`: alternativa com duas VMs e Keepalived.
