# Guia de demonstração com VMs

## Opção automática: Vagrant + VirtualBox

Instale Java 17 no computador cliente, VirtualBox e Vagrant. Na raiz do projeto:

```bash
cd infra
vagrant up
```

O provisionamento instala Java e Keepalived, cria o serviço `forca.service` e inicia os dois servidores.

Confira os serviços:

```bash
vagrant ssh primary -c "systemctl status forca --no-pager"
vagrant ssh backup -c "systemctl status forca --no-pager"
```

Confira quem possui o IP virtual:

```bash
vagrant ssh primary -c "ip address show eth1"
vagrant ssh backup -c "ip address show eth1"
```

Antes da falha, `192.168.56.100` deve aparecer na VM principal.

## Roteiro da apresentação

1. Abra quatro clientes para mostrar duas partidas simultâneas.
2. Em uma partida, erre uma letra com cada jogador e mostre os dois bonecos nas duas telas.
3. Tente jogar fora do turno e mostre a recusa.
4. Execute `vagrant halt -f primary` durante a partida.
5. Aguarde alguns segundos; os clientes mostrarão a tentativa de reconexão.
6. Continue jogando no mesmo estado.
7. Mostre que o IP virtual passou para a VM reserva.

## Configuração manual

Caso as VMs já existam, use esta rede:

| Máquina | IP |
|---|---|
| Principal | `192.168.56.11` |
| Reserva | `192.168.56.12` |
| Virtual | `192.168.56.100` |

Copie o projeto para as duas VMs e instale:

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless keepalived
```

Na principal, copie `infra/keepalived-primary.conf` para `/etc/keepalived/keepalived.conf`. Na reserva, use `infra/keepalived-backup.conf`. Se a placa da rede privada não for `eth1`, altere a linha `interface` nos dois arquivos.

Principal:

```bash
bash scripts/run-primary.sh 192.168.56.12
```

Reserva:

```bash
bash scripts/run-backup.sh
```

Libere TCP 5050 para jogadores, TCP 5051 somente entre as VMs e protocolo VRRP entre elas. Não exponha a porta de replicação à internet.

## Diagnóstico

Logs do servidor quando executado pelo Vagrant:

```bash
vagrant ssh primary -c "journalctl -u forca -n 100 --no-pager"
vagrant ssh backup -c "journalctl -u forca -n 100 --no-pager"
```

Estado do Keepalived:

```bash
vagrant ssh primary -c "systemctl status keepalived --no-pager"
vagrant ssh backup -c "systemctl status keepalived --no-pager"
```

Se o IP virtual não mudar, as causas mais comuns são interface incorreta, VRRP bloqueado pelo firewall ou as duas VMs não estarem na mesma rede privada.
