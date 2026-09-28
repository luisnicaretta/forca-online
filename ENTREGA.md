# Checklist de entrega — Jogo da Forca Distribuído

## Requisitos da atividade

- [x] Comunicação cliente/servidor por `Socket`/`ServerSocket` TCP.
- [x] Servidor resiliente com nó principal e reserva.
- [x] Alternativa Docker + HAProxy e alternativa VM + Keepalived/VRRP.
- [x] Sala de espera com múltiplos clientes.
- [x] Exatamente 2 jogadores por partida.
- [x] `Semaphore(1, true)` e validação do jogador da vez no servidor.
- [x] Estado transmitido aos dois clientes.
- [x] Erros/bonecos separados por jogador e visíveis para ambos.
- [x] Cronômetro de 120 s exibido para jogador e adversário.
- [x] Deadline validado no servidor, não apenas na interface.
- [x] Reconexão/failover preserva cronômetro e estado.
- [x] Proteção de standby por heartbeat para evitar promoção indevida.
- [x] Persistência SQLite opcional no modo Docker.
- [x] Revanche e identificação por token preservadas na replicação.

## Para demonstrar ao professor

1. Inicie Docker com `INICIAR_DOCKER.bat` (ou Vagrant conforme `docs/GUIA-VM.md`).
2. Abra 4 clientes e mostre 2 partidas simultâneas.
3. Mostre os dois bonecos nas duas telas.
4. Tente jogar fora do turno.
5. Mostre o cronômetro igual nas duas telas.
6. Derrube o primário e mostre a reconexão/continuação sem resetar o relógio.
7. Mostre o banco em `http://localhost:8080/stats` no modo Docker.
8. Se quiser comprovação automatizada, execute os três testes Python em `tests/`.
