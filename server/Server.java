import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Servidor TCP do jogo da forca para duas pessoas por partida.
 *
 * Execucao (Java 17 ou superior):
 *   java Server.java --port=5050 --role=primary --peer=127.0.0.1:5051
 *   java Server.java --port=5052 --role=backup --replication-port=5051
 */
public class Server {
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    static String enc(String value) {
        return B64E.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String dec(String value) {
        return new String(B64D.decode(value), StandardCharsets.UTF_8);
    }

    static void log(String message) {
        System.out.printf("[%s] %s%n", LocalDateTime.now(), message);
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        GameServer server = new GameServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }

    static final class Config {
        int port = 5050;
        String role = "primary";
        String peerHost;
        int peerPort = 5051;
        int replicationPort = 5051;
        String secret = "forca-replica-2026";
        String wordsFile = "words.txt";

        static Config parse(String[] args) {
            Config c = new Config();
            for (String arg : args) {
                if (arg.startsWith("--port=")) c.port = Integer.parseInt(arg.substring(7));
                else if (arg.startsWith("--role=")) c.role = arg.substring(7).toLowerCase(Locale.ROOT);
                else if (arg.startsWith("--replication-port=")) c.replicationPort = Integer.parseInt(arg.substring(19));
                else if (arg.startsWith("--secret=")) c.secret = arg.substring(9);
                else if (arg.startsWith("--words=")) c.wordsFile = arg.substring(8);
                else if (arg.startsWith("--peer=")) {
                    String[] address = arg.substring(7).split(":", 2);
                    c.peerHost = address[0];
                    if (address.length == 2) c.peerPort = Integer.parseInt(address[1]);
                } else if (arg.equals("--help")) {
                    System.out.println("Opcoes: --port=N --role=primary|backup --peer=HOST:PORT " +
                            "--replication-port=N --secret=CHAVE --words=ARQUIVO");
                    System.exit(0);
                }
            }
            if (!c.role.equals("primary") && !c.role.equals("backup")) {
                throw new IllegalArgumentException("--role deve ser primary ou backup");
            }
            return c;
        }
    }

    static final class GameServer {
        final Config config;
        final BlockingQueue<Player> waitingRoom = new LinkedBlockingQueue<>();
        final ConcurrentMap<String, Player> playersByToken = new ConcurrentHashMap<>();
        final ConcurrentMap<String, GameSession> games = new ConcurrentHashMap<>();
        final ExecutorService clients = Executors.newCachedThreadPool();
        final AtomicLong matchSequence = new AtomicLong(System.currentTimeMillis());
        final WordBank wordBank;
        final Replicator replicator;
        volatile boolean running = true;
        ServerSocket gameSocket;
        ReplicationReceiver receiver;

        GameServer(Config config) {
            this.config = config;
            this.wordBank = new WordBank(config.wordsFile);
            this.replicator = new Replicator(config);
        }

        void start() throws IOException {
            if (config.role.equals("backup")) {
                receiver = new ReplicationReceiver(this, config);
                clients.submit(receiver);
            }
            clients.submit(this::matchPlayers);
            gameSocket = new ServerSocket();
            gameSocket.setReuseAddress(true);
            gameSocket.bind(new InetSocketAddress(config.port));
            log("Servidor " + config.role.toUpperCase(Locale.ROOT) + " ouvindo jogadores na porta " + config.port);
            if (config.peerHost != null) log("Replicacao configurada para " + config.peerHost + ":" + config.peerPort);

            while (running) {
                try {
                    Socket socket = gameSocket.accept();
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    clients.submit(() -> handleClient(socket));
                } catch (SocketException e) {
                    if (running) log("Erro ao aceitar cliente: " + e.getMessage());
                }
            }
        }

        void handleClient(Socket socket) {
            Player player = null;
            Connection connection = null;
            try {
                connection = new Connection(socket);
                socket.setSoTimeout(15_000);
                String hello = connection.readLine();
                socket.setSoTimeout(0);
                if (hello == null) return;
                String[] fields = hello.split("\\|", -1);
                if (fields.length < 3 || !fields[0].equals("HELLO")) {
                    connection.send("ERROR|" + enc("Primeira mensagem deve ser HELLO|nome|token"));
                    return;
                }

                String name = sanitizeName(dec(fields[1]));
                String requestedToken = fields[2];
                if (!requestedToken.equals("-") && playersByToken.containsKey(requestedToken)) {
                    player = playersByToken.get(requestedToken);
                    if (!player.name.equalsIgnoreCase(name)) {
                        connection.send("ERROR|" + enc("Token pertence a outro jogador"));
                        return;
                    }
                    player.attach(connection);
                    connection.send("WELCOME|" + player.token + "|RECONNECTED");
                    if (player.game != null) player.game.onReconnect(player);
                    else {
                        connection.send("WAITING|" + waitingRoom.size());
                        if (!waitingRoom.contains(player)) waitingRoom.offer(player);
                    }
                    log(name + " reconectou com token " + shortToken(player.token));
                } else {
                    String token = UUID.randomUUID().toString();
                    player = new Player(name, token, connection);
                    playersByToken.put(token, player);
                    connection.send("WELCOME|" + token + "|NEW");
                    waitingRoom.put(player);
                    connection.send("WAITING|" + waitingRoom.size());
                    log(name + " entrou na sala de espera");
                }

                while (running && connection.isOpen()) {
                    String line = connection.readLine();
                    if (line == null) break;
                    processMessage(player, line);
                }
            } catch (SocketTimeoutException e) {
                if (connection != null) connection.send("ERROR|" + enc("Tempo esgotado antes da identificacao"));
            } catch (Exception e) {
                if (running) log("Conexao encerrada: " + e.getMessage());
            } finally {
                if (player != null && player.connection == connection) {
                    player.detach(connection);
                    if (player.game != null) player.game.onDisconnect(player);
                }
                if (connection != null) connection.close();
            }
        }

        void processMessage(Player player, String line) {
            String[] fields = line.split("\\|", -1);
            if (fields[0].equals("PING")) {
                player.send("PONG");
                return;
            }
            if (fields[0].equals("QUIT")) {
                if (player.connection != null) player.connection.close();
                return;
            }
            if (player.game == null) {
                player.send("ERROR|" + enc("Voce ainda esta na sala de espera"));
                return;
            }
            if (fields[0].equals("REPLAY")) {
                player.game.requestReplay(player);
                return;
            }
            if ((!fields[0].equals("GUESS") && !fields[0].equals("WORD")) || fields.length < 2) {
                player.send("ERROR|" + enc("Comando desconhecido"));
                return;
            }
            String guess;
            try {
                guess = dec(fields[1]).trim();
            } catch (IllegalArgumentException e) {
                player.send("ERROR|" + enc("Formato de letra invalido"));
                return;
            }
            if (fields[0].equals("WORD")) player.game.guessWord(player, guess);
            else player.game.guess(player, guess);
        }

        void matchPlayers() {
            while (running) {
                try {
                    Player p1 = takeConnectedWaitingPlayer();
                    Player p2 = takeConnectedWaitingPlayer();
                    String id = Long.toString(matchSequence.incrementAndGet());
                    WordEntry selected = wordBank.randomWord();
                    GameSession game = new GameSession(this, id, selected.word(), selected.category(), p1, p2);
                    games.put(id, game);
                    p1.game = game;
                    p2.game = game;
                    game.broadcast("Partida iniciada! " + p1.name + " comeca.");
                    game.replicate();
                    log("Partida " + id + ": " + p1.name + " x " + p2.name);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        Player takeConnectedWaitingPlayer() throws InterruptedException {
            while (true) {
                Player player = waitingRoom.take();
                if (player.game == null && player.isConnected()) return player;
            }
        }

        synchronized void applySnapshot(String payload) {
            try {
                GameSnapshot snapshot = GameSnapshot.parse(payload);
                GameSession current = games.get(snapshot.id);
                if (current != null && current.version >= snapshot.version) return;

                Player p1 = playersByToken.computeIfAbsent(snapshot.p1Token,
                        token -> new Player(snapshot.p1Name, token, null));
                Player p2 = playersByToken.computeIfAbsent(snapshot.p2Token,
                        token -> new Player(snapshot.p2Name, token, null));
                p1.errors = snapshot.p1Errors;
                p2.errors = snapshot.p2Errors;

                GameSession game = current;
                if (game == null) {
                    game = new GameSession(this, snapshot.id, snapshot.word, snapshot.category, p1, p2);
                    games.put(snapshot.id, game);
                    p1.game = game;
                    p2.game = game;
                }
                game.restore(snapshot);
                log("Replica atualizada: partida " + snapshot.id + ", versao " + snapshot.version);
            } catch (Exception e) {
                log("Snapshot rejeitado: " + e.getMessage());
            }
        }

        void close() {
            running = false;
            try { if (gameSocket != null) gameSocket.close(); } catch (IOException ignored) {}
            if (receiver != null) receiver.close();
            clients.shutdownNow();
        }

        static String sanitizeName(String input) {
            String cleaned = input == null ? "" : input.replace("|", "").trim();
            if (cleaned.isEmpty()) throw new IllegalArgumentException("Nome vazio");
            return cleaned.substring(0, Math.min(24, cleaned.length()));
        }
    }

    static final class Player {
        final String name;
        final String token;
        volatile Connection connection;
        volatile GameSession game;
        volatile int errors;

        Player(String name, String token, Connection connection) {
            this.name = name;
            this.token = token;
            this.connection = connection;
        }

        synchronized void attach(Connection next) {
            Connection old = connection;
            connection = next;
            if (old != null && old != next) old.close();
        }

        synchronized void detach(Connection expected) {
            if (connection == expected) connection = null;
        }

        boolean isConnected() {
            return connection != null && connection.isOpen();
        }

        void send(String message) {
            Connection c = connection;
            if (c != null) c.send(message);
        }
    }

    static final class Connection {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final Object writeLock = new Object();

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        String readLine() throws IOException { return in.readLine(); }

        void send(String message) {
            synchronized (writeLock) {
                if (!socket.isClosed()) out.println(message);
            }
        }

        boolean isOpen() { return !socket.isClosed(); }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    static final class GameSession {
        static final int MAX_ERRORS = 6;
        final GameServer server;
        final String id;
        final String word;
        final String category;
        final Player[] players;
        final Set<Character> guessed = new TreeSet<>();
        final Set<String> replayReady = new HashSet<>();
        final Semaphore semaphore = new Semaphore(1, true);
        final ReentrantLock stateLock = new ReentrantLock();
        volatile int turnIndex;
        volatile String status = "PLAYING";
        volatile String winnerToken = "-";
        volatile long version = 1;

        GameSession(GameServer server, String id, String word, String category, Player p1, Player p2) {
            this.server = server;
            this.id = id;
            this.word = normalize(word);
            this.category = category == null || category.isBlank() ? "GERAL" : category.toUpperCase(Locale.ROOT);
            this.players = new Player[]{p1, p2};
        }

        void guess(Player player, String rawGuess) {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                stateLock.lock();
                try {
                    if (!status.equals("PLAYING")) {
                        player.send("ERROR|" + enc("A partida ja terminou"));
                        return;
                    }
                    if (players[turnIndex] != player) {
                        player.send("ERROR|" + enc("Aguarde sua vez"));
                        return;
                    }
                    String normalized = normalize(rawGuess);
                    if (normalized.codePointCount(0, normalized.length()) != 1 || !Character.isLetter(normalized.charAt(0))) {
                        player.send("ERROR|" + enc("Digite somente uma letra"));
                        return;
                    }
                    char letter = normalized.charAt(0);
                    if (guessed.contains(letter)) {
                        player.send("ERROR|" + enc("Essa letra ja foi utilizada"));
                        return;
                    }
                    guessed.add(letter);
                    String message;
                    if (word.indexOf(letter) >= 0) {
                        message = player.name + " acertou a letra " + letter + ".";
                    } else {
                        player.errors++;
                        message = player.name + " errou a letra " + letter + ".";
                    }

                    if (isWordComplete()) {
                        status = "FINISHED";
                        winnerToken = player.token;
                        message += " Palavra descoberta!";
                    } else if (player.errors >= MAX_ERRORS) {
                        status = "FINISHED";
                        winnerToken = other(player).token;
                        message += " " + player.name + " completou a forca.";
                    } else {
                        turnIndex = 1 - turnIndex;
                    }
                    version++;
                    broadcast(message);
                    replicate();
                } finally {
                    stateLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) semaphore.release();
            }
        }

        void guessWord(Player player, String rawGuess) {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                stateLock.lock();
                try {
                    if (!status.equals("PLAYING")) {
                        player.send("ERROR|" + enc("A partida ja terminou"));
                        return;
                    }
                    if (players[turnIndex] != player) {
                        player.send("ERROR|" + enc("Aguarde sua vez"));
                        return;
                    }
                    String attempt = normalize(rawGuess);
                    if (attempt.length() < 2 || !attempt.chars().anyMatch(Character::isLetter)) {
                        player.send("ERROR|" + enc("Digite uma palavra valida"));
                        return;
                    }

                    String message;
                    if (attempt.equals(word)) {
                        status = "FINISHED";
                        winnerToken = player.token;
                        message = player.name + " acertou a palavra inteira!";
                    } else {
                        player.errors++;
                        message = player.name + " errou a palavra " + attempt + ".";
                        if (player.errors >= MAX_ERRORS) {
                            status = "FINISHED";
                            winnerToken = other(player).token;
                            message += " " + player.name + " completou a forca.";
                        } else {
                            turnIndex = 1 - turnIndex;
                        }
                    }
                    version++;
                    broadcast(message);
                    replicate();
                } finally {
                    stateLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) semaphore.release();
            }
        }

        void requestReplay(Player player) {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                stateLock.lock();
                try {
                    if (!status.equals("FINISHED")) {
                        player.send("ERROR|" + enc("A partida ainda nao terminou"));
                        return;
                    }
                    if (!replayReady.add(player.token)) return;
                    if (replayReady.size() < 2) {
                        broadcast(player.name + " quer jogar novamente. Aguardando o adversario...");
                        return;
                    }

                    players[0].errors = 0;
                    players[1].errors = 0;
                    WordEntry selected = server.wordBank.randomWord();
                    String nextId = Long.toString(server.matchSequence.incrementAndGet());
                    GameSession next = new GameSession(server, nextId, selected.word(), selected.category(), players[0], players[1]);
                    server.games.put(nextId, next);
                    players[0].game = next;
                    players[1].game = next;
                    server.games.remove(id, this);
                    next.broadcast("Nova partida iniciada! " + players[0].name + " comeca.");
                    next.replicate();
                    log("Revanche " + nextId + ": " + players[0].name + " x " + players[1].name);
                } finally {
                    stateLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) semaphore.release();
            }
        }

        void onReconnect(Player player) {
            broadcast(player.name + " reconectou à partida.");
        }

        void onDisconnect(Player player) {
            broadcast(player.name + " perdeu a conexao; aguardando reconexao.");
        }

        void broadcast(String message) {
            String state = stateMessage(message);
            players[0].send(state);
            players[1].send(state);
        }

        String stateMessage(String message) {
            String masked = maskedWord();
            String letters = guessed.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            return String.join("|",
                    "STATE", id, enc(masked), enc(players[0].name), Integer.toString(players[0].errors),
                    enc(players[1].name), Integer.toString(players[1].errors), players[turnIndex].token,
                    enc(letters), status, winnerToken, enc(message), Long.toString(version),
                    enc(status.equals("FINISHED") ? word : ""), enc(category));
        }

        String maskedWord() {
            StringBuilder value = new StringBuilder();
            for (char c : word.toCharArray()) {
                if (!Character.isLetter(c) || guessed.contains(c)) value.append(c);
                else value.append('_');
                value.append(' ');
            }
            return value.toString().trim();
        }

        boolean isWordComplete() {
            for (char c : word.toCharArray()) {
                if (Character.isLetter(c) && !guessed.contains(c)) return false;
            }
            return true;
        }

        Player other(Player player) { return players[0] == player ? players[1] : players[0]; }

        void replicate() {
            if (server.config.peerHost != null) server.replicator.send(snapshot().serialize());
        }

        GameSnapshot snapshot() {
            return new GameSnapshot(id, word, players[0].name, players[0].token, players[0].errors,
                    players[1].name, players[1].token, players[1].errors,
                    guessedString(), turnIndex, status, winnerToken, version, category);
        }

        String guessedString() {
            StringBuilder result = new StringBuilder();
            for (char c : guessed) result.append(c);
            return result.toString();
        }

        void restore(GameSnapshot s) {
            stateLock.lock();
            try {
                guessed.clear();
                for (char c : s.guessed.toCharArray()) guessed.add(c);
                players[0].errors = s.p1Errors;
                players[1].errors = s.p2Errors;
                turnIndex = s.turnIndex;
                status = s.status;
                winnerToken = s.winnerToken;
                version = s.version;
            } finally {
                stateLock.unlock();
            }
        }

        static String normalize(String text) {
            String withoutAccents = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            return withoutAccents.toUpperCase(Locale.ROOT);
        }
    }

    record GameSnapshot(String id, String word,
                        String p1Name, String p1Token, int p1Errors,
                        String p2Name, String p2Token, int p2Errors,
                        String guessed, int turnIndex, String status,
                        String winnerToken, long version, String category) {
        String serialize() {
            return String.join("|", id, enc(word), enc(p1Name), p1Token, Integer.toString(p1Errors),
                    enc(p2Name), p2Token, Integer.toString(p2Errors), enc(guessed),
                    Integer.toString(turnIndex), status, winnerToken, Long.toString(version), enc(category));
        }

        static GameSnapshot parse(String line) {
            String[] f = line.split("\\|", -1);
            if (f.length != 13 && f.length != 14) throw new IllegalArgumentException("snapshot incompleto");
            return new GameSnapshot(f[0], dec(f[1]), dec(f[2]), f[3], Integer.parseInt(f[4]),
                    dec(f[5]), f[6], Integer.parseInt(f[7]), dec(f[8]), Integer.parseInt(f[9]),
                    f[10], f[11], Long.parseLong(f[12]), f.length == 14 ? dec(f[13]) : "GERAL");
        }
    }

    static final class Replicator {
        final Config config;

        Replicator(Config config) { this.config = config; }

        void send(String snapshot) {
            CompletableFuture.runAsync(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(config.peerHost, config.peerPort), 1200);
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    out.println("SYNC|" + enc(config.secret) + "|" + snapshot);
                    socket.setSoTimeout(1200);
                    String response = in.readLine();
                    if (!"OK".equals(response)) log("Replica nao confirmou o snapshot");
                } catch (IOException e) {
                    log("Aviso: servidor reserva indisponivel: " + e.getMessage());
                }
            });
        }
    }

    static final class ReplicationReceiver implements Runnable {
        final GameServer server;
        final Config config;
        volatile boolean running = true;
        ServerSocket socket;

        ReplicationReceiver(GameServer server, Config config) {
            this.server = server;
            this.config = config;
        }

        public void run() {
            try {
                socket = new ServerSocket(config.replicationPort);
                log("Receptor de replicas ouvindo na porta " + config.replicationPort);
                while (running) {
                    try (Socket peer = socket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                         PrintWriter out = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                        peer.setSoTimeout(3000);
                        String line = in.readLine();
                        if (line == null) continue;
                        String[] parts = line.split("\\|", 3);
                        if (parts.length != 3 || !parts[0].equals("SYNC") || !dec(parts[1]).equals(config.secret)) {
                            out.println("DENIED");
                            continue;
                        }
                        server.applySnapshot(parts[2]);
                        out.println("OK");
                    } catch (SocketException e) {
                        if (running) log("Falha no receptor: " + e.getMessage());
                    } catch (Exception e) {
                        log("Replica invalida: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running) log("Nao foi possivel abrir porta de replica: " + e.getMessage());
            }
        }

        void close() {
            running = false;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }
    }

    record WordEntry(String category, String word) { }

    static final class WordBank {
        final List<WordEntry> words = new ArrayList<>();
        final Random random = new Random();

        WordBank(String filename) {
            File file = new File(filename);
            if (!file.exists()) file = new File("server", filename);
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    reader.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).forEach(line -> {
                        String[] fields = line.split("\\|", 2);
                        if (fields.length == 2 && !fields[0].isBlank() && !fields[1].isBlank()) {
                            words.add(new WordEntry(fields[0].trim(), fields[1].trim()));
                        } else {
                            words.add(new WordEntry("GERAL", line));
                        }
                    });
                } catch (IOException e) {
                    log("Nao foi possivel ler palavras: " + e.getMessage());
                }
            }
            if (words.isEmpty()) words.addAll(List.of(
                    new WordEntry("TECNOLOGIA", "COMPUTADOR"),
                    new WordEntry("TECNOLOGIA", "ALGORITMO"),
                    new WordEntry("TECNOLOGIA", "INTERNET"),
                    new WordEntry("TECNOLOGIA", "SERVIDOR")));
        }

        WordEntry randomWord() { return words.get(random.nextInt(words.size())); }
    }

    static String shortToken(String token) {
        return token.substring(0, Math.min(8, token.length()));
    }
}
