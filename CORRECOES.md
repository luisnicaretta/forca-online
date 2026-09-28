# Correções realizadas

Esta versão corrige os problemas encontrados na revisão de lógica e concorrência:

- deadline validado dentro de `guess`/`guessWord`, impedindo jogada após `00:00`;
- `turnStartedAt` preservado no failover;
- backup protegido por heartbeat e promoção somente após perda do primário;
- `STATE` envia tokens dos dois jogadores, permitindo nomes iguais;
- `replayReady` é replicado e sobrevive ao failover;
- último autor da jogada é identificado por token na GUI;
- modo Online não inicia cluster local escondido;
- cliente de terminal exibe o tempo recebido do servidor;
- replicação bidirecional opcional para VMs e atraso de preempção no retorno do primário;
- acesso direto à porta 5050 dos IPs reais das VMs é bloqueado no Vagrant;
- banco aceita o formato ampliado de snapshot;
- nova suíte `tests/resilience_test.py` cobre as regressões acima.

Validação executada: `integration_test.py`, `walkover_test.py`, `resilience_test.py`, autoteste gráfico, compilação Java, sintaxe do Vagrantfile e parse do `docker-compose.yml`.
