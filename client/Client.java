import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cliente de terminal com reconexao automatica. */
public class Client {
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private final String name;
    private final List<Address> servers;
    private final AtomicReference<PrintWriter> output = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile String token = "-";
    private volatile boolean connected;

    Client(String name, List<Address> servers) {
        this.name = name;
        this.servers = servers;
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        String name = options.get("name");
        if (name == null || name.isBlank()) {
            System.out.print("Seu nome: ");
            name = scanner.nextLine().trim();
        }
        if (name.isBlank()) name = "Jogador";
        String serverList = options.getOrDefault("servers", "127.0.0.1:5050");
        List<Address> addresses = new ArrayList<>();
        for (String item : serverList.split(",")) addresses.add(Address.parse(item.trim()));

        Client client = new Client(name, addresses);
        Thread network = new Thread(client::connectionLoop, "conexao-servidor");
        network.setDaemon(true);
        network.start();
        client.inputLoop(scanner);
    }

    void connectionLoop() {
        int nextServer = 0;
        while (running.get()) {
            Address address = servers.get(nextServer++ % servers.size());
            try (Socket socket = new Socket()) {
                System.out.println("Conectando a " + address.host + ":" + address.port + "...");
                socket.connect(new InetSocketAddress(address.host, address.port), 2500);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                output.set(out);
                connected = true;
                out.println("HELLO|" + enc(name) + "|" + token);
                String line;
                while (running.get() && (line = in.readLine()) != null) handle(line);
            } catch (IOException e) {
                if (running.get()) System.out.println("Servidor indisponivel; tentando novamente...");
            } finally {
                connected = false;
                output.set(null);
            }
            sleep(1500);
        }
    }

    void inputLoop(Scanner scanner) {
        System.out.println("Digite uma letra quando for sua vez. Use /sair para encerrar.");
        while (running.get() && scanner.hasNextLine()) {
            String value = scanner.nextLine().trim();
            if (value.equalsIgnoreCase("/sair")) {
                send("QUIT");
                running.set(false);
                return;
            }
            if (value.isEmpty()) continue;
            if (!connected) {
                System.out.println("Aguardando reconexao; a letra nao foi enviada.");
                continue;
            }
            send("GUESS|" + enc(value));
        }
    }

    void handle(String line) {
        try {
            String[] f = line.split("\\|", -1);
            switch (f[0]) {
                case "WELCOME" -> {
                    token = f[1];
                    System.out.println(f.length > 2 && f[2].equals("RECONNECTED")
                            ? "Reconectado. Recuperando a partida..." : "Conectado ao servidor.");
                }
                case "WAITING" -> System.out.println(f.length > 2 ? dec(f[2])
                        : "Sala de espera: aguardando outro jogador...");
                case "STATE" -> renderState(f);
                case "ERROR" -> System.out.println("AVISO: " + dec(f[1]));
                case "PONG" -> { }
                default -> System.out.println("Mensagem desconhecida do servidor.");
            }
        } catch (Exception e) {
            System.out.println("Falha ao interpretar resposta: " + e.getMessage());
        }
    }

    void renderState(String[] f) {
        if (f.length < 14) return;
        String matchId = f[1];
        String masked = dec(f[2]);
        String p1 = dec(f[3]);
        int e1 = Integer.parseInt(f[4]);
        String p2 = dec(f[5]);
        int e2 = Integer.parseInt(f[6]);
        String turnToken = f[7];
        String letters = dec(f[8]);
        String status = f[9];
        String winnerToken = f[10];
        String message = dec(f[11]);
        String word = dec(f[13]);

        clearScreen();
        System.out.println("JOGO DA FORCA ONLINE    Partida " + matchId);
        System.out.println("Palavra: " + masked);
        System.out.println("Letras usadas: " + (letters.isBlank() ? "nenhuma" : letters));
        System.out.println();
        System.out.println(p1 + " - erros: " + e1 + "/6");
        System.out.println(drawHangman(e1));
        System.out.println(p2 + " - erros: " + e2 + "/6");
        System.out.println(drawHangman(e2));
        System.out.println(message);

        if (status.equals("FINISHED")) {
            System.out.println("Palavra: " + word);
            System.out.println(winnerToken.equals(token) ? "VOCE VENCEU!" : "VOCE PERDEU.");
        } else if (turnToken.equals(token)) {
            System.out.println("SUA VEZ: digite uma letra e pressione Enter.");
        } else {
            System.out.println("Aguarde a jogada do adversario...");
        }
    }

    static String drawHangman(int errors) {
        String head = errors >= 1 ? "O" : " ";
        String leftArm = errors >= 3 ? "/" : " ";
        String body = errors >= 2 ? "|" : " ";
        String rightArm = errors >= 4 ? "\\" : " ";
        String leftLeg = errors >= 5 ? "/" : " ";
        String rightLeg = errors >= 6 ? "\\" : " ";
        return " +---+\n" +
               " |   |\n" +
               " " + head + "   |\n" +
               leftArm + body + rightArm + "  |\n" +
               leftLeg + " " + rightLeg + "  |\n" +
               "     |\n" +
               "=========";
    }

    void send(String value) {
        PrintWriter writer = output.get();
        if (writer != null) writer.println(value);
    }

    static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static String enc(String value) {
        return B64E.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String dec(String value) {
        return new String(B64D.decode(value), StandardCharsets.UTF_8);
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--name=")) result.put("name", arg.substring(7));
            else if (arg.startsWith("--servers=")) result.put("servers", arg.substring(10));
            else if (arg.equals("--help")) {
                System.out.println("Uso: java Client.java --name=Luis --servers=IP_VIRTUAL:5050");
                System.exit(0);
            }
        }
        return result;
    }

    record Address(String host, int port) {
        static Address parse(String value) {
            String[] f = value.split(":", 2);
            return new Address(f[0], f.length == 2 ? Integer.parseInt(f[1]) : 5050);
        }
    }
}
