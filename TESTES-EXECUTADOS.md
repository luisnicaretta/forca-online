# Testes executados nesta entrega

Comandos executados com sucesso no ambiente de validação:

```bash
javac -encoding UTF-8 -d build-all server/Server.java client/Client.java client/GameLauncher.java
python3 -m py_compile database/db_service.py tests/integration_test.py tests/walkover_test.py tests/resilience_test.py
python3 tests/integration_test.py
python3 tests/walkover_test.py
python3 tests/resilience_test.py
java client/GameLauncher.java --network-check
ruby -c infra/Vagrantfile
```

Cobertura principal:

- sockets TCP e sala de espera;
- 4 jogadores formando 2 partidas;
- máximo de 2 jogadores por partida;
- semáforo e recusa fora do turno;
- dois bonecos independentes sincronizados;
- persistência SQLite e restauração;
- replicação e continuação no reserva;
- cronômetro igual nos dois clientes;
- reconexão e failover sem reiniciar o cronômetro;
- jogada que chega depois do deadline gera W.O. e não é aceita;
- backup em standby rejeita cliente enquanto recebe heartbeat do primário;
- jogadores com nomes iguais são identificados por token;
- pedido de revanche sobrevive ao failover;
- saída voluntária e timeout geram W.O.;
- cliente gráfico/bot continua usando sockets reais.

## Limitação do ambiente

O Docker Engine e o VirtualBox não estão instalados no ambiente de validação, portanto os containers/VMs não foram inicializados aqui. O comportamento de alta disponibilidade da aplicação foi exercitado com processos Java separados, sockets TCP reais, porta de replicação real, heartbeat, queda do primário e reconexão no reserva. O `docker-compose.yml` e o `Vagrantfile` foram validados estruturalmente/sintaticamente.
