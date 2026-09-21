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
        int graceSeconds = 30;
        int turnTimeoutSeconds = 120;

        static Config parse(String[] args) {
            Config c = new Config();
            for (String arg : args) {
                if (arg.startsWith("--port=")) c.port = Integer.parseInt(arg.substring(7));
                else if (arg.startsWith("--role=")) c.role = arg.substring(7).toLowerCase(Locale.ROOT);
                else if (arg.startsWith("--replication-port=")) c.replicationPort = Integer.parseInt(arg.substring(19));
                else if (arg.startsWith("--secret=")) c.secret = arg.substring(9);
                else if (arg.startsWith("--words=")) c.wordsFile = arg.substring(8);
                else if (arg.startsWith("--grace=")) c.graceSeconds = Integer.parseInt(arg.substring(8));
                else if (arg.startsWith("--turn-timeout=")) c.turnTimeoutSeconds = Integer.parseInt(arg.substring(15));
                else if (arg.startsWith("--peer=")) {
                    String[] address = arg.substring(7).split(":", 2);
                    c.peerHost = address[0];
                    if (address.length == 2) c.peerPort = Integer.parseInt(address[1]);
                } else if (arg.equals("--help")) {
                    System.out.println("Opcoes: --port=N --role=primary|backup --peer=HOST:PORT " +
                            "--replication-port=N --secret=CHAVE --words=ARQUIVO " +
                            "--grace=SEG (espera por reconexao, padrao 30) --turn-timeout=SEG (0 desativa, padrao 120)");
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
        final Object lobbyLock = new Object();
        final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog-wo");
            t.setDaemon(true);
            return t;
        });
        /** O reserva so passa a cobrar W.O. depois que assume (primeiro cliente conectado). */
        volatile boolean active;
        volatile boolean running = true;
        ServerSocket gameSocket;
        ReplicationReceiver receiver;

        GameServer(Config config) {
            this.config = config;
            this.wordBank = new WordBank(config.wordsFile);
            this.replicator = new Replicator(config);
            this.active = config.role.equals("primary");
        }

        void start() throws IOException {
            if (config.role.equals("backup")) {
                receiver = new ReplicationReceiver(this, config);
                clients.submit(receiver);
            }
            clients.submit(this::matchPlayers);
            watchdog.scheduleWithFixedDelay(() -> {
                try { watchdogTick(); } catch (Exception e) { log("Erro no watchdog: " + e); }
            }, 1, 1, TimeUnit.SECONDS);
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
                activate();
                Player existing = requestedToken.equals("-") ? null : playersByToken.get(requestedToken);
                if (existing != null) {
                    player = existing;
                    if (!player.name.equalsIgnoreCase(name)) {
                        connection.send("ERROR|" + enc("Token pertence a outro jogador"));
                        return;
                    }
                    player.attach(connection);
                    connection.send("WELCOME|" + player.token + "|RECONNECTED");
                    if (player.game != null) player.game.onReconnect(player);
                    else {
                        enqueue(player);
                        connection.send("WAITING|" + waitingRoom.size());
                    }
                    log(name + " reconectou com token " + shortToken(player.token));
                } else {
                    String token = UUID.randomUUID().toString();
                    player = new Player(name, token, connection);
                    playersByToken.put(token, player);
                    connection.send("WELCOME|" + token + "|NEW");
                    enqueue(player);
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
                player.left = true;
                GameSession g = player.game;
                if (g != null) g.onQuit(player);
                else forget(player);
                Connection c = player.connection;
                if (c != null) c.close();
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

        void enqueue(Player player) {
            synchronized (lobbyLock) {
                if (player.game == null && !waitingRoom.contains(player)) waitingRoom.offer(player);
            }
        }

        static boolean isMatchable(Player p) {
            return p != null && p.game == null && p.isConnected();
        }

       void matchPlayers() {
            Player first = null;
            while (running) {
                try {
                    if (first == null) first = takeConnectedWaitingPlayer();
                    Player second = waitingRoom.poll(500, TimeUnit.MILLISECONDS);
                    if (!isMatchable(first)) {
                        first = isMatchable(second) ? second : null;
                        continue;
                    }
                    if (second == null || second == first || !isMatchable(second)) continue;

                    Player p1 = first;
                    Player p2 = second;
                    first = null;
                    p1.errors = 0;
                    p2.errors = 0;
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
                if (isMatchable(player)) return player;
            }
        }

        synchronized void activate() {
            if (active) return;
            active = true;
            long now = System.currentTimeMillis();
            for (Player p : playersByToken.values()) if (!p.isConnected()) p.disconnectedSince = now;
            for (GameSession g : games.values()) g.turnStartedAt = now;
            log("Reserva assumiu o servico; controle de W.O. ativado.");
        }

        void watchdogTick() {
            if (!active || !running) return;
            long now = System.currentTimeMillis();
            for (GameSession g : new ArrayList<>(games.values())) g.checkTimeouts(now);
            long graceMs = config.graceSeconds * 1000L;
            for (Player p : new ArrayList<>(playersByToken.values())) {
                if (p.isConnected()) continue;
                boolean expired = p.left || (p.disconnectedSince > 0 && now - p.disconnectedSince > graceMs);
                if (!expired) continue;
                GameSession g = p.game;
                if (g == null || g.isFinished()) forget(p);
            }
        }

        void forget(Player p) {
            synchronized (lobbyLock) { waitingRoom.remove(p); }
            playersByToken.remove(p.token, p);
            GameSession g = p.game;
            if (g != null) {
                Player o = g.other(p);
                if (playersByToken.get(o.token) != o) games.remove(g.id, g);
            }
            log(p.name + " removido (saiu ou nao reconectou)");
        }

        synchronized void applySnapshot(String payload) {
            try {
                GameSnapshot snapshot = GameSnapshot.parse(payload);
                GameSession current = games.get(snapshot.id);
                if (current != null && current.version >= snapshot.version) return;
                matchSequence.accumulateAndGet(Long.parseLong(snapshot.id), Math::max);

                Player p1 = playersByToken.computeIfAbsent(snapshot.p1Token,
                        token -> new Player(snapshot.p1Name, token, null));
                Player p2 = playersByToken.computeIfAbsent(snapshot.p2Token,
                        token -> new Player(snapshot.p2Name, token, null));

                if (isOlder(p1, snapshot.id) || isOlder(p2, snapshot.id)) return;

                GameSession game = current;
                if (game == null) {
                    for (Player p : List.of(p1, p2)) {
                        if (p.game != null) games.remove(p.game.id, p.game);
                    }
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

        static boolean isOlder(Player p, String snapshotId) {
            GameSession g = p.game;
            return g != null && Long.parseLong(g.id) > Long.parseLong(snapshotId);
        }

        void close() {
            running = false;
            try { if (gameSocket != null) gameSocket.close(); } catch (IOException ignored) {}
            if (receiver != null) receiver.close();
            watchdog.shutdownNow();
            replicator.close();
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
        volatile long disconnectedSince;
        volatile boolean left;

        Player(String name, String token, Connection connection) {
            this.name = name;
            this.token = token;
            this.connection = connection;
        }

        synchronized void attach(Connection next) {
            Connection old = connection;
            connection = next;
            disconnectedSince = 0;
            left = false;
            if (old != null && old != next) old.close();
        }

        synchronized void detach(Connection expected) {
            if (connection == expected) {
                connection = null;
                disconnectedSince = System.currentTimeMillis();
            }
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
        volatile long turnStartedAt = System.currentTimeMillis();
        volatile String lastMessage = "";

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
                        turnStartedAt = System.currentTimeMillis();
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
                        turnStartedAt = System.currentTimeMillis();
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
                    Player opponent = other(player);
                    if (server.games.get(id) != this || opponent.left || !opponent.isConnected()) {
                        requeueLocked(player, "O adversario saiu.");
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
            if (players[turnIndex] == player) turnStartedAt = System.currentTimeMillis();
            broadcast(status.equals("PLAYING") ? player.name + " reconectou a partida." : lastMessage);
        }

        void onDisconnect(Player player) {
            if (player.left || !status.equals("PLAYING")) return;
            broadcast(player.name + " perdeu a conexao; tem " + server.config.graceSeconds
                    + "s para voltar antes do W.O.");
        }

        void onQuit(Player player) {
            locked(() -> {
                if (status.equals("PLAYING")) {
                    forfeitLocked(player, player.name + " saiu da partida. Vitoria por W.O.!");
                } else {
                    Player opponent = other(player);
                    if (replayReady.contains(opponent.token)) requeueLocked(opponent, "O adversario saiu.");
                }
            });
        }

        boolean isFinished() { return status.equals("FINISHED"); }

        boolean unavailable(Player p, long now) {
            if (p.left) return true;
            return !p.isConnected() && p.disconnectedSince > 0
                    && now - p.disconnectedSince > server.config.graceSeconds * 1000L;
        }

        void checkTimeouts(long now) {
            locked(() -> {
                if (status.equals("PLAYING")) {
                    Player a = players[0], b = players[1];
                    boolean aGone = unavailable(a, now), bGone = unavailable(b, now);
                    if (aGone || bGone) {
                        Player loser = aGone && bGone ? (a.disconnectedSince <= b.disconnectedSince ? a : b)
                                : (aGone ? a : b);
                        forfeitLocked(loser, loser.name + " ficou offline por mais de "
                                + server.config.graceSeconds + "s. Vitoria por W.O.!");
                        return;
                    }
                    int limit = server.config.turnTimeoutSeconds;
                    if (limit > 0 && now - turnStartedAt > limit * 1000L) {
                        Player current = players[turnIndex];
                        forfeitLocked(current, current.name + " nao jogou em " + limit
                                + "s. Vitoria por W.O.!");
                    }
                } else {
                    for (Player x : players) {
                        if (replayReady.contains(x.token) && unavailable(other(x), now)) {
                            requeueLocked(x, "O adversario saiu.");
                        }
                    }
                }
            });
        }

        private void forfeitLocked(Player loser, String message) {
            status = "FINISHED";
            winnerToken = other(loser).token;
            version++;
            broadcast(message);
            replicate();
            log("Partida " + id + " encerrada por W.O.: " + loser.name + " perdeu");
        }

        private void requeueLocked(Player player, String why) {
            replayReady.clear();
            server.games.remove(id, this);
            player.game = null;
            player.errors = 0;
            server.enqueue(player);
            player.send("WAITING|" + server.waitingRoom.size() + "|" + enc(why + " Procurando novo adversario..."));
            log(player.name + " voltou para a sala de espera (adversario saiu)");
        }

        private void locked(Runnable action) {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                stateLock.lock();
                try {
                    action.run();
                } finally {
                    stateLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) semaphore.release();
            }
        }

        void broadcast(String message) {
            stateLock.lock();
            try {
                lastMessage = message;
                String state = stateMessage(message);
                players[0].send(state);
                players[1].send(state);
            } finally {
                stateLock.unlock();
            }
        }

        String stateMessage(String message) {
            stateLock.lock();
            try {
                String masked = maskedWord();
                String letters = guessed.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                return String.join("|",
                        "STATE", id, enc(masked), enc(players[0].name), Integer.toString(players[0].errors),
                        enc(players[1].name), Integer.toString(players[1].errors), players[turnIndex].token,
                        enc(letters), status, winnerToken, enc(message), Long.toString(version),
                        enc(status.equals("FINISHED") ? word : ""), enc(category));
            } finally {
                stateLock.unlock();
            }
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
            if (server.config.peerHost != null) server.replicator.send(id, snapshot().serialize());
        }

        GameSnapshot snapshot() {
            stateLock.lock();
            try {
                return buildSnapshot();
            } finally {
                stateLock.unlock();
            }
        }

        private GameSnapshot buildSnapshot() {
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
                turnStartedAt = System.currentTimeMillis();
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
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "replicador");
            t.setDaemon(true);
            return t;
        });
        final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
        boolean scheduled;

        Replicator(Config config) { this.config = config; }

        void send(String gameId, String snapshot) {
            synchronized (pending) {
                pending.remove(gameId);
                pending.put(gameId, snapshot);
                if (scheduled) return;
                scheduled = true;
            }
            try {
                worker.execute(this::drain);
            } catch (RejectedExecutionException ignored) { }
        }

        private void drain() {
            while (true) {
                String snapshot;
                synchronized (pending) {
                    Iterator<String> it = pending.values().iterator();
                    if (!it.hasNext()) {
                        scheduled = false;
                        return;
                    }
                    snapshot = it.next();
                    it.remove();
                }
                deliver(snapshot);
            }
        }

        private void deliver(String snapshot) {
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
        }

        void close() { worker.shutdownNow(); }
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
