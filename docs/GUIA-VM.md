# Guia de demonstração com VMs

## Vagrant + VirtualBox

Pré-requisitos: Java 17 no cliente, VirtualBox e Vagrant. Na raiz do projeto:

```bash
cd infra
vagrant up
```

O provisionamento instala Java e Keepalived e inicia os dois servidores. Os clientes devem usar **somente** o IP virtual `192.168.56.100:5050`; a porta 5050 nos IPs reais das VMs é bloqueada para evitar acesso direto a um nó fora do fluxo de alta disponibilidade.

| Máquina | IP real | Papel inicial |
|---|---|---|
| primary | `192.168.56.11` | prioridade VRRP 150 |
| backup | `192.168.56.12` | prioridade VRRP 100 |
| serviço | `192.168.56.100` | IP virtual dos clientes |

Confira o VIP:

```bash
vagrant ssh primary -c "ip address show eth1"
vagrant ssh backup -c "ip address show eth1"
```

## Como a resiliência funciona

- ambos os nós mantêm receptor de replicação;
- o nó ativo envia snapshots ao outro nó;
- o primário envia heartbeats enquanto está ativo;
- o backup rejeita clientes enquanto os heartbeats do primário estão recentes;
- quando o primário cai, Keepalived move o VIP e, após a perda dos heartbeats, o backup se promove;
- `turnStartedAt` é preservado: o cronômetro não volta para 120 s;
- se o primário voltar, `preempt_delay 10` dá tempo para ele receber snapshots reversos do backup antes de tentar recuperar o VIP.

## Roteiro da apresentação

1. Abra quatro clientes em `192.168.56.100:5050` e mostre duas partidas.
2. Erre uma jogada com cada jogador e mostre os dois bonecos em ambas as telas.
3. Tente jogar fora do turno e mostre a recusa.
4. Observe o mesmo cronômetro nos dois clientes.
5. Execute `vagrant halt -f primary` durante a partida.
6. Os clientes reconectam no mesmo VIP e continuam no estado replicado, sem reset do cronômetro.
7. Confira que `192.168.56.100` está na VM backup.
8. Opcional: `vagrant up primary`. Aguarde a sincronização/failback e confira novamente o VIP.

## Execução manual

Principal:

```bash
bash scripts/run-primary.sh 192.168.56.12
```

Reserva:

```bash
bash scripts/run-backup.sh 192.168.56.11
```

Copie `infra/keepalived-primary.conf` e `infra/keepalived-backup.conf` para `/etc/keepalived/keepalived.conf` em cada nó. Na configuração manual, também restrinja TCP 5050 para o destino VIP `192.168.56.100`; TCP 5051 deve ser acessível somente entre os dois servidores. VRRP também precisa estar liberado entre as VMs.

## Diagnóstico

```bash
vagrant ssh primary -c "journalctl -u forca -n 100 --no-pager"
vagrant ssh backup -c "journalctl -u forca -n 100 --no-pager"
vagrant ssh primary -c "systemctl status keepalived --no-pager"
vagrant ssh backup -c "systemctl status keepalived --no-pager"
```

Se o VIP não mudar, verifique a interface (`eth1`), o firewall/VRRP e se as duas VMs estão na mesma rede privada.
