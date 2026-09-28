# Guia de demonstração com Docker

Este é o caminho recomendado para apresentar o trabalho sem depender de VirtualBox/Vagrant.

## 1. Pré-requisitos

- Java 17 ou superior no computador que abrirá os clientes;
- Docker Desktop (Windows/macOS) ou Docker Engine + Docker Compose (Linux).

Na raiz do projeto, execute:

```bash
docker compose up -d --build
```

No Windows também é possível executar:

```text
scripts\docker-start.bat
```

O Compose sobe quatro serviços:

- `primary`: servidor Java principal;
- `backup`: servidor Java reserva;
- `proxy`: HAProxy, ponto único acessado pelos clientes em `127.0.0.1:5050`;
- `database`: serviço de persistência SQLite, com volume Docker.

## 2. Abrir os jogadores

Abra `Jogar_Online.bat` duas vezes. Em ambos, mantenha o servidor como:

```text
127.0.0.1:5050
```

Use nomes diferentes. O primeiro jogador fica na sala de espera e o segundo forma a partida.

Para mostrar múltiplas requisições, abra quatro clientes. Os jogadores 1 e 2 formarão uma partida e os jogadores 3 e 4 outra.

## 3. O que mostrar durante a partida

1. Somente o jogador indicado como "SUA VEZ" consegue enviar uma jogada.
2. Cada erro aumenta somente o boneco do jogador que errou.
3. Os dois clientes recebem o mesmo estado e enxergam os dois bonecos.
4. A palavra, categoria, letras usadas, turno e placar são enviados pelo servidor.
5. O cliente não decide vitória, erro ou troca de turno; ele somente envia `GUESS`/`WORD`.

O teste automatizado também prova que uma requisição enviada fora do turno é recusada pelo servidor:

```bash
python tests/integration_test.py
```

## 4. Demonstrar failover

Com uma partida em andamento, execute:

```bash
docker compose stop primary
```

ou no Windows:

```text
scripts\docker-failover.bat
```

A conexão TCP atual cai. O cliente tenta novamente `127.0.0.1:5050`. O HAProxy detecta que o `primary` está indisponível e passa a conexão para o `backup`.

O cliente envia o mesmo token, o reserva encontra a partida replicada e o jogo continua com palavra, erros, letras e turno preservados.

Para acompanhar visualmente o estado do HAProxy, abra:

```text
http://localhost:8404
```

## 5. Demonstrar o banco de dados

A persistência fica em um volume Docker e usa SQLite. Para ver um resumo:

```bash
curl http://localhost:8080/stats
```

Exemplo de resposta:

```json
{
  "matches": 2,
  "active": 2,
  "finished": 0,
  "snapshots": 8
}
```

Para listar partidas registradas:

```bash
curl http://localhost:8080/matches
```

O banco registra o último snapshot de cada partida e um histórico de versões. Ao iniciar, um servidor pode restaurar as partidas ainda marcadas como `PLAYING`.

## 6. Restaurar o principal (failback controlado)

Durante uma partida ativa, use o script de failback em vez de simplesmente iniciar o primário sozinho:

```text
scripts\docker-restore-primary.bat
```

ou:

```bash
bash scripts/docker-restore-primary.sh
```

O script para o backup por um instante para congelar o estado e permitir que a fila de persistência seja descarregada, inicia o primário (que restaura partidas ativas do banco) e depois volta a iniciar o backup como reserva. Os clientes podem mostrar reconexão durante essa troca.

O `primary` está com reinício automático desativado no Compose justamente para evitar dois servidores processando a mesma partida após um failover.

## 7. Encerrar

```bash
docker compose down
```

Isso preserva o volume do banco. Para apagar também os dados persistidos:

```bash
docker compose down -v
```

## 8. Roteiro de 3 minutos para o professor

1. `docker compose up -d --build`.
2. Abra quatro clientes e mostre duas partidas simultâneas.
3. Em uma partida, erre uma jogada em cada cliente e mostre que os dois bonecos aparecem nas duas telas.
4. Explique que `Semaphore(1, true)` e a validação do token controlam a região crítica e o turno.
5. Execute `docker compose stop primary` com a partida aberta.
6. Mostre "RECONECTANDO" e depois a continuação da mesma partida no reserva.
7. Abra `http://localhost:8080/stats` para mostrar a persistência.
8. Se quiser comprovação automática, rode `python tests/integration_test.py`.
