import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Cliente grafico do projeto. Tanto o humano quanto o bot usam o protocolo TCP
 * do Server.java; nenhuma regra da partida e decidida na interface.
 */
public class GameLauncher {
    static final Color BG = new Color(12, 17, 27);
    static final Color CARD = new Color(25, 33, 49);
    static final Color CARD_2 = new Color(35, 45, 65);
    static final Color TEXT = new Color(239, 243, 250);
    static final Color MUTED = new Color(151, 164, 185);
    static final Color GOLD = new Color(247, 193, 70);
    static final Color GREEN = new Color(72, 210, 151);
    static final Color RED = new Color(244, 96, 105);
    static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder B64D = Base64.getUrlDecoder();
    static final List<Process> MANAGED_PROCESSES = new CopyOnWriteArrayList<>();
    static final List<BotClient> MANAGED_BOTS = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--check")) {
            System.out.println("GameLauncher compilado com sucesso.");
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(GameLauncher::shutdownManaged));
        if (args.length > 0 && args[0].equals("--network-check")) {
            try {
                runNetworkSelfTest();
                System.out.println("Cliente gráfico/bot: sockets, sala, turnos e estado validados.");
            } catch (Exception e) {
                System.err.println("Falha no teste gráfico/bot: " + rootMessage(e));
                System.exit(1);
            } finally {
                shutdownManaged();
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new LauncherFrame().setVisible(true);
        });
    }

    static String enc(String value) {
        return B64E.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String dec(String value) {
        return new String(B64D.decode(value), StandardCharsets.UTF_8);
    }

    static void shutdownManaged() {
        for (BotClient bot : MANAGED_BOTS) bot.close();
        for (Process process : MANAGED_PROCESSES) {
            if (process.isAlive()) process.destroy();
        }
    }

    static void runNetworkSelfTest() throws Exception {
        List<Address> addresses = LocalCluster.startFreshCluster();
        BotClient bot = new BotClient("BOT_TESTE", addresses);
        MANAGED_BOTS.add(bot);
        try (Socket human = new Socket()) {
            Address primary = addresses.get(0);
            human.connect(new InetSocketAddress(primary.host, primary.port), 2500);
            human.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(human.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(human.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println("HELLO|" + enc("HUMANO_TESTE") + "|-");
            String welcome = readUntil(in, "WELCOME");
            String humanToken = welcome.split("\\|", -1)[1];
            bot.start();
            String[] initial = readUntil(in, "STATE").split("\\|", -1);
            if (!initial[7].equals(humanToken)) throw new IllegalStateException("turno inicial incorreto");
            if (initial.length < 15 || dec(initial[14]).isBlank()) throw new IllegalStateException("categoria não recebida");
            out.println("GUESS|" + enc("Z"));
            String[] afterHuman = readUntil(in, "STATE").split("\\|", -1);
            long humanVersion = Long.parseLong(afterHuman[12]);
            String[] afterBot = readUntil(in, "STATE").split("\\|", -1);
            long botVersion = Long.parseLong(afterBot[12]);
            if (botVersion <= humanVersion) throw new IllegalStateException("bot não realizou a jogada por socket");
            if (!dec(afterBot[5]).equals("BOT_TESTE")) throw new IllegalStateException("segundo jogador não é o bot");
            out.println("QUIT");
        } finally {
            bot.close();
        }
    }

    static String readUntil(BufferedReader in, String kind) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith(kind + "|")) return line;
        }
        throw new EOFException("conexão fechada aguardando " + kind);
    }

    static final class LauncherFrame extends JFrame {
        final JTextField nameField = input("Jogador");
        final JTextField serverField = input("127.0.0.1:5050");
        final JLabel feedback = label("Escolha como deseja jogar", 14, MUTED, Font.PLAIN);
        final JButton soloButton;
        final JButton onlineButton;

        LauncherFrame() {
            super("Jogo da Forca");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(760, 590);
            setMinimumSize(new Dimension(700, 550));
            setLocationRelativeTo(null);
            setContentPane(build());
            soloButton = findButton("SOLO CONTRA BOT");
            onlineButton = findButton("JOGAR ONLINE");
        }

        private JComponent build() {
            JPanel root = new JPanel(new BorderLayout(0, 20));
            root.setBackground(BG);
            root.setBorder(new EmptyBorder(32, 45, 36, 45));

            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            JLabel title = label("JOGO DA FORCA", 34, TEXT, Font.BOLD);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            JLabel subtitle = label("Cliente gráfico • Sockets TCP • Alta disponibilidade", 14, MUTED, Font.PLAIN);
            subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            header.add(title);
            header.add(Box.createVerticalStrut(6));
            header.add(subtitle);
            root.add(header, BorderLayout.NORTH);

            JPanel content = new RoundedPanel(28, CARD);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(new EmptyBorder(28, 34, 30, 34));
            content.add(fieldGroup("SEU NOME", nameField));
            content.add(Box.createVerticalStrut(18));
            content.add(fieldGroup("SERVIDOR DO MODO ONLINE", serverField));
            content.add(Box.createVerticalStrut(24));

            JPanel actions = new JPanel(new GridLayout(1, 2, 14, 0));
            actions.setOpaque(false);
            JButton solo = actionButton("SOLO CONTRA BOT", "Servidor + bot automáticos", GOLD, BG);
            solo.setName("SOLO CONTRA BOT");
            solo.addActionListener(e -> startSolo(solo));
            JButton online = actionButton("JOGAR ONLINE", "Aguarda outro jogador", GREEN, BG);
            online.setName("JOGAR ONLINE");
            online.addActionListener(e -> startOnline(online));
            actions.add(solo);
            actions.add(online);
            content.add(actions);
            content.add(Box.createVerticalStrut(21));
            feedback.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(feedback);
            root.add(content, BorderLayout.CENTER);

            JLabel note = label("No solo, você e o bot são clientes distintos conectados ao mesmo servidor.", 12, MUTED, Font.PLAIN);
            note.setHorizontalAlignment(SwingConstants.CENTER);
            root.add(note, BorderLayout.SOUTH);
            return root;
        }

        private JButton findButton(String name) {
            return findButtonRecursive(getContentPane(), name);
        }

        private JButton findButtonRecursive(Container container, String name) {
            for (Component component : container.getComponents()) {
                if (component instanceof JButton b && name.equals(b.getName())) return b;
                if (component instanceof Container child) {
                    JButton found = findButtonRecursive(child, name);
                    if (found != null) return found;
                }
            }
            return null;
        }

        private JComponent fieldGroup(String title, JTextField field) {
            JPanel group = new JPanel(new BorderLayout(0, 7));
            group.setOpaque(false);
            group.add(label(title, 11, MUTED, Font.BOLD), BorderLayout.NORTH);
            group.add(field, BorderLayout.CENTER);
            group.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
            return group;
        }

        private void startSolo(JButton source) {
            String name = validName();
            if (name == null) return;
            setBusy(true, "Iniciando servidor principal, reserva e bot...");
            new SwingWorker<List<Address>, Void>() {
                @Override protected List<Address> doInBackground() throws Exception {
                    return LocalCluster.startFreshCluster();
                }

                @Override protected void done() {
                    try {
                        List<Address> addresses = get();
                        GameFrame game = new GameFrame(name, addresses, "MODO SOLO CONTRA BOT");
                        game.setVisible(true);
                        dispose();
                        javax.swing.Timer timer = new javax.swing.Timer(700, e -> {
                            BotClient bot = new BotClient("BOT", addresses);
                            MANAGED_BOTS.add(bot);
                            bot.start();
                        });
                        timer.setRepeats(false);
                        timer.start();
                    } catch (Exception ex) {
                        setBusy(false, "Não foi possível iniciar: " + rootMessage(ex));
                    }
                }
            }.execute();
        }

        private void startOnline(JButton source) {
            String name = validName();
            if (name == null) return;
            String servers = serverField.getText().trim();
            if (servers.isEmpty()) {
                feedback.setText("Informe o IP do servidor.");
                return;
            }
            setBusy(true, "Conectando ao servidor...");
            new SwingWorker<List<Address>, Void>() {
                @Override protected List<Address> doInBackground() throws Exception {
                    List<Address> addresses = Address.parseList(servers);
                    Address first = addresses.get(0);
                    if (first.isLocal() && !LocalCluster.isPortOpen(first.port, 250)) {
                        LocalCluster.ensureRunning();
                        return Address.parseList("127.0.0.1:5050,127.0.0.1:5052");
                    }
                    return addresses;
                }

                @Override protected void done() {
                    try {
                        GameFrame game = new GameFrame(name, get(), "MODO ONLINE");
                        game.setVisible(true);
                        dispose();
                    } catch (Exception ex) {
                        setBusy(false, "Falha: " + rootMessage(ex));
                    }
                }
            }.execute();
        }

        private String validName() {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                feedback.setForeground(RED);
                feedback.setText("Digite seu nome.");
                return null;
            }
            return name.substring(0, Math.min(24, name.length()));
        }

        private void setBusy(boolean busy, String message) {
            nameField.setEnabled(!busy);
            serverField.setEnabled(!busy);
            if (soloButton != null) soloButton.setEnabled(!busy);
            if (onlineButton != null) onlineButton.setEnabled(!busy);
            feedback.setForeground(busy ? GOLD : RED);
            feedback.setText(message);
        }
    }

    static final class GameFrame extends JFrame {
        final String name;
        final List<Address> addresses;
        final JLabel connection = label("CONECTANDO...", 12, GOLD, Font.BOLD);
        final JLabel category = label("CATEGORIA: AGUARDANDO", 12, GOLD, Font.BOLD);
        final JLabel word = label("_ _ _ _ _", 34, TEXT, Font.BOLD);
        final JLabel letters = label("Letras usadas: nenhuma", 13, MUTED, Font.PLAIN);
        final JLabel message = label("Aguardando servidor...", 15, TEXT, Font.BOLD);
        final JLabel turn = label("", 14, GOLD, Font.BOLD);
        final JTextField wholeWordInput = input("");
        final JButton wordGuessButton = new GameActionButton("ADIVINHAR PALAVRA", GOLD, BG);
        final JButton replayButton = new GameActionButton("JOGAR DE NOVO", GREEN, BG);
        final HangmanView player1 = new HangmanView();
        final HangmanView player2 = new HangmanView();
        final Map<Character, JButton> keyboard = new LinkedHashMap<>();
        final AtomicReference<PrintWriter> writer = new AtomicReference<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile String token = "-";
        volatile String usedLetters = "";
        volatile boolean myTurn;
        volatile boolean gameFinished;
        volatile long lastDisplayedVersion;
        volatile String currentMatchId = "";
        volatile boolean replayRequested;
        volatile int serverIndex;
        Thread networkThread;
        JPanel wordGuessPanel;

        GameFrame(String name, List<Address> addresses, String mode) {
            super("Jogo da Forca — " + name);
            this.name = name;
            this.addresses = addresses;
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(1120, 760);
            setMinimumSize(new Dimension(940, 680));
            setLocationRelativeTo(null);
            setContentPane(build(mode));
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) { close(); }
            });
            networkThread = new Thread(this::connectionLoop, "cliente-grafico");
            networkThread.setDaemon(true);
            networkThread.start();
        }

        private JComponent build(String mode) {
            JPanel root = new JPanel(new BorderLayout(18, 16));
            root.setBackground(BG);
            root.setBorder(new EmptyBorder(18, 22, 20, 22));

            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            JPanel titleBox = new JPanel();
            titleBox.setOpaque(false);
            titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
            titleBox.add(label("JOGO DA FORCA", 27, TEXT, Font.BOLD));
            titleBox.add(label(mode + "  •  Jogador: " + name, 12, MUTED, Font.PLAIN));
            header.add(titleBox, BorderLayout.CENTER);
            header.add(connection, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            JPanel players = new JPanel(new GridLayout(1, 2, 16, 0));
            players.setOpaque(false);
            players.add(card(player1, 16));
            players.add(card(player2, 16));
            root.add(players, BorderLayout.CENTER);

            JPanel gameArea = new RoundedPanel(24, CARD);
            gameArea.setBorder(new EmptyBorder(16, 22, 18, 22));
            gameArea.setLayout(new BoxLayout(gameArea, BoxLayout.Y_AXIS));
            word.setFont(new Font(Font.MONOSPACED, Font.BOLD, 34));
            for (JLabel item : List.of(category, word, letters, message, turn)) item.setAlignmentX(Component.CENTER_ALIGNMENT);
            gameArea.add(category);
            gameArea.add(Box.createVerticalStrut(5));
            gameArea.add(word);
            gameArea.add(Box.createVerticalStrut(5));
            gameArea.add(letters);
            gameArea.add(Box.createVerticalStrut(8));
            gameArea.add(message);
            gameArea.add(Box.createVerticalStrut(4));
            gameArea.add(turn);
            gameArea.add(Box.createVerticalStrut(8));
            gameArea.add(buildWordGuessPanel());
            replayButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            replayButton.setPreferredSize(new Dimension(260, 38));
            replayButton.setMaximumSize(new Dimension(260, 38));
            replayButton.setVisible(false);
            replayButton.addActionListener(e -> requestReplay());
            gameArea.add(replayButton);
            gameArea.add(Box.createVerticalStrut(9));
            gameArea.add(buildKeyboard());
            root.add(gameArea, BorderLayout.SOUTH);
            return root;
        }

        private JComponent buildKeyboard() {
            JPanel panel = new JPanel(new GridLayout(2, 13, 6, 6));
            panel.setOpaque(false);
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
            for (char c = 'A'; c <= 'Z'; c++) {
                JButton button = new LetterButton(c);
                button.setEnabled(false);
                char letter = c;
                button.addActionListener(e -> sendGuess(letter));
                keyboard.put(c, button);
                panel.add(button);
            }
            return panel;
        }

        private JComponent buildWordGuessPanel() {
            wordGuessPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 9, 0));
            wordGuessPanel.setOpaque(false);
            JLabel prompt = label("Já sabe a resposta?", 12, MUTED, Font.BOLD);
            wholeWordInput.setPreferredSize(new Dimension(270, 38));
            wholeWordInput.setToolTipText("Digite a palavra ou expressão completa");
            wholeWordInput.addActionListener(e -> sendWordGuess());
            wordGuessButton.setPreferredSize(new Dimension(185, 38));
            wordGuessButton.addActionListener(e -> sendWordGuess());
            wordGuessPanel.add(prompt);
            wordGuessPanel.add(wholeWordInput);
            wordGuessPanel.add(wordGuessButton);
            wordGuessPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            return wordGuessPanel;
        }

        private void connectionLoop() {
            while (running.get()) {
                Address address = addresses.get(serverIndex++ % addresses.size());
                updateConnection("CONECTANDO A " + address.host + ":" + address.port, GOLD);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address.host, address.port), 2500);
                    socket.setKeepAlive(true);
                    socket.setTcpNoDelay(true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    writer.set(out);
                    out.println("HELLO|" + enc(name) + "|" + token);
                    updateConnection("CONECTADO", GREEN);
                    String line;
                    while (running.get() && (line = in.readLine()) != null) handle(line);
                } catch (IOException e) {
                    if (running.get()) updateConnection("RECONECTANDO...", RED);
                } finally {
                    writer.set(null);
                    SwingUtilities.invokeLater(() -> setGuessControlsEnabled(false));
                }
                sleep(1200);
            }
        }

        private void handle(String line) {
            try {
                String[] f = line.split("\\|", -1);
                switch (f[0]) {
                    case "WELCOME" -> token = f[1];
                    case "WAITING" -> {
                        gameFinished = false;
                        replayRequested = false;
                        myTurn = false;
                        String waitingInfo = f.length > 2 ? dec(f[2]) : "Você está na sala de espera.";
                        SwingUtilities.invokeLater(() -> {
                            currentMatchId = "";
                            replayButton.setText("JOGAR DE NOVO");
                            replayButton.setVisible(false);
                            message.setText(waitingInfo);
                            message.setForeground(TEXT);
                            turn.setText("Aguardando o segundo jogador...");
                            setGuessControlsEnabled(false);
                        });
                    }
                    case "ERROR" -> SwingUtilities.invokeLater(() -> {
                        message.setText(dec(f[1]));
                        message.setForeground(RED);
                        if (myTurn && !gameFinished) refreshKeyboard();
                    });
                    case "STATE" -> showState(f);
                    default -> { }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> message.setText("Resposta inválida do servidor."));
            }
        }

        private void showState(String[] f) {
            if (f.length < 14) return;
            String matchId = f[1];
            String masked = dec(f[2]);
            String p1Name = dec(f[3]);
            int p1Errors = Integer.parseInt(f[4]);
            String p2Name = dec(f[5]);
            int p2Errors = Integer.parseInt(f[6]);
            String turnToken = f[7];
            String used = dec(f[8]);
            String status = f[9];
            String winnerToken = f[10];
            String info = dec(f[11]);
            long version = Long.parseLong(f[12]);
            String secretWord = dec(f[13]);
            String categoryName = f.length >= 15 ? dec(f[14]) : "GERAL";
            myTurn = status.equals("PLAYING") && token.equals(turnToken);
            gameFinished = status.equals("FINISHED");
            usedLetters = used;

            SwingUtilities.invokeLater(() -> {
                if (!matchId.equals(currentMatchId)) {
                    currentMatchId = matchId;
                    lastDisplayedVersion = 0;
                    replayRequested = false;
                    replayButton.setText("JOGAR DE NOVO");
                    wholeWordInput.setText("");
                }
                word.setText(masked);
                category.setText("CATEGORIA: " + categoryName);
                letters.setText("Letras usadas: " + (used.isBlank() ? "nenhuma" : used));
                message.setText(info);
                message.setForeground(gameFinished ? (winnerToken.equals(token) ? GREEN : RED) : TEXT);
                player1.update(p1Name, p1Errors, p1Name.equalsIgnoreCase(name));
                player2.update(p2Name, p2Errors, p2Name.equalsIgnoreCase(name));
                if (version > lastDisplayedVersion && info.startsWith(p1Name + " acertou")) {
                    player1.showTaunt();
                } else if (version > lastDisplayedVersion && info.startsWith(p2Name + " acertou")) {
                    player2.showTaunt();
                }
                lastDisplayedVersion = Math.max(lastDisplayedVersion, version);
                if (gameFinished) {
                    word.setText(secretWord.replace("", " ").trim());
                    turn.setText(winnerToken.equals(token) ? "VOCÊ VENCEU!" : "VOCÊ PERDEU");
                    wordGuessPanel.setVisible(false);
                    replayButton.setVisible(true);
                    replayButton.setEnabled(!replayRequested);
                } else {
                    turn.setText(myTurn ? "SUA VEZ — ESCOLHA UMA LETRA" : "VEZ DO ADVERSÁRIO");
                    wordGuessPanel.setVisible(true);
                    replayButton.setVisible(false);
                }
                refreshKeyboard();
            });
        }

        private void sendGuess(char letter) {
            if (!myTurn || gameFinished) return;
            PrintWriter out = writer.get();
            if (out != null) out.println("GUESS|" + enc(String.valueOf(letter)));
            setGuessControlsEnabled(false);
        }

        private void sendWordGuess() {
            String attempt = wholeWordInput.getText().trim();
            if (!myTurn || gameFinished || attempt.length() < 2) {
                if (attempt.length() < 2) message.setText("Digite a palavra completa antes de enviar.");
                return;
            }
            PrintWriter out = writer.get();
            if (out != null) out.println("WORD|" + enc(attempt));
            wholeWordInput.setText("");
            setGuessControlsEnabled(false);
        }

        private void requestReplay() {
            if (!gameFinished || replayRequested) return;
            PrintWriter out = writer.get();
            if (out != null) {
                out.println("REPLAY");
                replayRequested = true;
                replayButton.setEnabled(false);
                replayButton.setText("AGUARDANDO ADVERSÁRIO...");
            }
        }

        private void refreshKeyboard() {
            Set<Character> used = new HashSet<>();
            for (String value : usedLetters.split(",")) if (!value.isBlank()) used.add(value.charAt(0));
            for (Map.Entry<Character, JButton> entry : keyboard.entrySet()) {
                boolean alreadyUsed = used.contains(entry.getKey());
                if (entry.getValue() instanceof LetterButton letterButton) letterButton.setUsed(alreadyUsed);
                entry.getValue().setEnabled(myTurn && !gameFinished && !alreadyUsed && writer.get() != null);
            }
            boolean canGuess = myTurn && !gameFinished && writer.get() != null;
            wholeWordInput.setEnabled(canGuess);
            wordGuessButton.setEnabled(canGuess);
        }

        private void setGuessControlsEnabled(boolean enabled) {
            for (JButton button : keyboard.values()) {
                boolean used = button instanceof LetterButton letterButton && letterButton.used;
                button.setEnabled(enabled && !used);
            }
            wholeWordInput.setEnabled(enabled);
            wordGuessButton.setEnabled(enabled);
        }

        private void updateConnection(String value, Color color) {
            SwingUtilities.invokeLater(() -> {
                connection.setText(value);
                connection.setForeground(color);
            });
        }

        private void close() {
            running.set(false);
            PrintWriter out = writer.get();
            if (out != null) out.println("QUIT");
        }
    }

    /** Segundo cliente real por socket usado no modo solo. */
    static final class BotClient {
        final String name;
        final List<Address> addresses;
        final AtomicBoolean running = new AtomicBoolean(true);
        final Random random = new Random();
        volatile String token = "-";
        volatile String lastScheduledTurn = "";
        volatile String lastReplayMatch = "";
        int serverIndex;
        Thread thread;

        BotClient(String name, List<Address> addresses) {
            this.name = name;
            this.addresses = addresses;
        }

        void start() {
            thread = new Thread(this::loop, "jogador-bot");
            thread.setDaemon(true);
            thread.start();
        }

        void loop() {
            while (running.get()) {
                Address address = addresses.get(serverIndex++ % addresses.size());
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address.host, address.port), 2500);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    out.println("HELLO|" + enc(name) + "|" + token);
                    String line;
                    while (running.get() && (line = in.readLine()) != null) {
                        String[] f = line.split("\\|", -1);
                        if (f[0].equals("WELCOME")) token = f[1];
                        else if (f[0].equals("STATE") && f.length >= 14) actIfNeeded(f, out);
                    }
                } catch (IOException ignored) { }
                sleep(1200);
            }
        }

        void actIfNeeded(String[] f, PrintWriter out) {
            String status = f[9];
            String turnToken = f[7];
            long version = Long.parseLong(f[12]);
            String matchId = f[1];
            if (status.equals("FINISHED")) {
                if (!matchId.equals(lastReplayMatch)) {
                    lastReplayMatch = matchId;
                    sleep(550);
                    if (running.get()) out.println("REPLAY");
                }
                return;
            }
            String turnKey = matchId + ":" + version;
            if (!status.equals("PLAYING") || !turnToken.equals(token) || turnKey.equals(lastScheduledTurn)) return;
            lastScheduledTurn = turnKey;
            String usedText = dec(f[8]);
            Set<Character> used = new HashSet<>();
            for (String value : usedText.split(",")) if (!value.isBlank()) used.add(value.charAt(0));
            List<Character> choices = new ArrayList<>();
            for (char c = 'A'; c <= 'Z'; c++) if (!used.contains(c)) choices.add(c);
            if (choices.isEmpty()) return;
            char choice = choices.get(random.nextInt(choices.size()));
            sleep(850);
            if (running.get()) out.println("GUESS|" + enc(String.valueOf(choice)));
        }

        void close() { running.set(false); }
    }

    static final class LocalCluster {
        private static final Object LOCK = new Object();

        static List<Address> startFreshCluster() throws Exception {
            synchronized (LOCK) {
                File serverSource = new File("server", "Server.java");
                if (!serverSource.exists()) throw new FileNotFoundException("server/Server.java não encontrado");

                int[] ports = reserveFreePorts();
                int primaryPort = ports[0];
                int backupPort = ports[1];
                int replicationPort = ports[2];

                Process backup = startProcess("server/Server.java", "--role=backup",
                        "--port=" + backupPort, "--replication-port=" + replicationPort);
                MANAGED_PROCESSES.add(backup);
                waitPort(replicationPort, 20_000);

                Process primary = startProcess("server/Server.java", "--role=primary",
                        "--port=" + primaryPort, "--peer=127.0.0.1:" + replicationPort);
                MANAGED_PROCESSES.add(primary);
                waitPort(primaryPort, 20_000);

                return List.of(new Address("127.0.0.1", primaryPort),
                        new Address("127.0.0.1", backupPort));
            }
        }

        private static int[] reserveFreePorts() throws IOException {
            try (ServerSocket first = new ServerSocket(0);
                 ServerSocket second = new ServerSocket(0);
                 ServerSocket third = new ServerSocket(0)) {
                return new int[] {
                        first.getLocalPort(), second.getLocalPort(), third.getLocalPort()
                };
            }
        }

        static void ensureRunning() throws Exception {
            synchronized (LOCK) {
                if (isPortOpen(5050, 300)) return;
                File serverSource = new File("server", "Server.java");
                if (!serverSource.exists()) throw new FileNotFoundException("server/Server.java não encontrado");

                Process backup = startProcess("server/Server.java", "--role=backup", "--port=5052", "--replication-port=5051");
                MANAGED_PROCESSES.add(backup);
                waitPort(5051, 20_000);
                Process primary = startProcess("server/Server.java", "--role=primary", "--port=5050", "--peer=127.0.0.1:5051");
                MANAGED_PROCESSES.add(primary);
                waitPort(5050, 20_000);
            }
        }

        static Process startProcess(String... args) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.addAll(Arrays.asList(args));
            return new ProcessBuilder(command)
                    .directory(new File("."))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }

        static String javaExecutable() {
            String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", executable).toString();
        }

        static void waitPort(int port, long timeout) throws Exception {
            long end = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < end) {
                if (isPortOpen(port, 250)) return;
                sleep(180);
            }
            throw new IOException("a porta " + port + " não abriu");
        }

        static boolean isPortOpen(int port, int timeout) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), timeout);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    record Address(String host, int port) {
        static List<Address> parseList(String text) {
            List<Address> result = new ArrayList<>();
            for (String item : text.split(",")) {
                String[] value = item.trim().split(":", 2);
                if (value[0].isBlank()) continue;
                result.add(new Address(value[0], value.length == 2 ? Integer.parseInt(value[1]) : 5050));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("endereço do servidor vazio");
            return result;
        }

        boolean isLocal() {
            return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost");
        }
    }

    static final class HangmanView extends JPanel {
        String playerName = "AGUARDANDO...";
        int errors;
        boolean me;
        int tauntType = -1;
        javax.swing.Timer tauntTimer;

        HangmanView() {
            setOpaque(false);
            setPreferredSize(new Dimension(420, 365));
        }

        void update(String name, int errors, boolean me) {
            this.playerName = name;
            this.errors = errors;
            this.me = me;
            repaint();
        }

        void showTaunt() {
            tauntType = ThreadLocalRandom.current().nextInt(4);
            if (tauntTimer != null) tauntTimer.stop();
            tauntTimer = new javax.swing.Timer(1700, e -> {
                tauntType = -1;
                repaint();
            });
            tauntTimer.setRepeats(false);
            tauntTimer.start();
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            g.setColor(me ? GOLD : GREEN);
            g.fillOval(14, 6, 52, 52);
            String initial = playerName.isBlank() ? "?" : playerName.substring(0, 1).toUpperCase(Locale.ROOT);
            g.setFont(new Font("SansSerif", Font.BOLD, 23));
            g.setColor(BG);
            g.drawString(initial, 40 - g.getFontMetrics().stringWidth(initial) / 2, 41);

            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.setColor(me ? GOLD : TEXT);
            String title = playerName + (me ? "  (VOCÊ)" : "");
            g.drawString(title, 80, 25);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(errors >= 5 ? RED : MUTED);
            String counter = "ERROS: " + errors + " / 6";
            g.drawString(counter, 80, 48);

            if (tauntType >= 0) paintColorReaction(g, w - 82, 1, tauntType);

            int cx = w / 2;
            int top = 68;
            g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(94, 109, 135));
            g.drawLine(cx - 145, top + 275, cx + 135, top + 275);
            g.drawLine(cx - 105, top + 275, cx - 105, top);
            g.drawLine(cx - 105, top, cx + 65, top);
            g.drawLine(cx + 65, top, cx + 65, top + 35);
            g.drawLine(cx - 105, top + 50, cx - 55, top);

            g.setColor(GOLD);
            g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (errors >= 1) g.drawOval(cx + 32, top + 35, 66, 66);
            if (errors >= 2) g.drawLine(cx + 65, top + 101, cx + 65, top + 195);
            if (errors >= 3) g.drawLine(cx + 65, top + 125, cx + 15, top + 168);
            if (errors >= 4) g.drawLine(cx + 65, top + 125, cx + 115, top + 168);
            if (errors >= 5) g.drawLine(cx + 65, top + 195, cx + 20, top + 252);
            if (errors >= 6) g.drawLine(cx + 65, top + 195, cx + 110, top + 252);
            g.dispose();
        }

        private void paintColorReaction(Graphics2D g, int x, int y, int type) {
            g.setColor(new Color(247, 249, 253));
            g.fillRoundRect(x, y, 68, 60, 20, 20);
            g.setColor(new Color(247, 193, 70));
            g.setStroke(new BasicStroke(3));
            g.drawRoundRect(x, y, 68, 60, 20, 20);
            switch (type) {
                case 0 -> paintCoolFace(g, x + 11, y + 7);
                case 1 -> paintFlame(g, x + 17, y + 5);
                case 2 -> paintLaughFace(g, x + 11, y + 7);
                default -> paintTrophy(g, x + 14, y + 6);
            }
        }

        private void paintCoolFace(Graphics2D g, int x, int y) {
            g.setColor(new Color(255, 199, 45));
            g.fillOval(x, y, 46, 46);
            g.setColor(new Color(20, 29, 45));
            g.fillRoundRect(x + 5, y + 13, 15, 10, 4, 4);
            g.fillRoundRect(x + 26, y + 13, 15, 10, 4, 4);
            g.setStroke(new BasicStroke(3));
            g.drawLine(x + 20, y + 16, x + 26, y + 16);
            g.drawArc(x + 13, y + 24, 21, 13, 190, 160);
        }

        private void paintFlame(Graphics2D g, int x, int y) {
            Polygon outer = new Polygon(
                    new int[] {x + 17, x + 8, x + 5, x + 10, x + 2, x + 3, x + 12, x + 26, x + 34, x + 36, x + 29},
                    new int[] {y, y + 15, y + 24, y + 29, y + 28, y + 39, y + 49, y + 50, y + 44, y + 31, y + 18}, 11);
            g.setColor(new Color(239, 70, 59));
            g.fillPolygon(outer);
            Polygon inner = new Polygon(
                    new int[] {x + 19, x + 12, x + 14, x + 9, x + 12, x + 20, x + 27, x + 29, x + 25},
                    new int[] {y + 17, y + 27, y + 32, y + 35, y + 44, y + 47, y + 42, y + 34, y + 26}, 9);
            g.setColor(new Color(255, 193, 45));
            g.fillPolygon(inner);
        }

        private void paintLaughFace(Graphics2D g, int x, int y) {
            g.setColor(new Color(255, 199, 45));
            g.fillOval(x, y, 46, 46);
            g.setColor(new Color(26, 35, 51));
            g.setStroke(new BasicStroke(3));
            g.drawArc(x + 7, y + 13, 11, 8, 15, 150);
            g.drawArc(x + 27, y + 13, 11, 8, 15, 150);
            g.fillArc(x + 12, y + 21, 23, 18, 180, 180);
            g.setColor(new Color(42, 158, 245));
            g.fillOval(x + 1, y + 21, 7, 12);
            g.fillOval(x + 38, y + 21, 7, 12);
        }

        private void paintTrophy(Graphics2D g, int x, int y) {
            g.setColor(new Color(255, 190, 35));
            g.fillRoundRect(x + 8, y + 2, 24, 25, 7, 7);
            g.setStroke(new BasicStroke(4));
            g.drawArc(x, y + 5, 16, 19, 80, 200);
            g.drawArc(x + 24, y + 5, 16, 19, -100, 200);
            g.fillRect(x + 18, y + 25, 5, 12);
            g.fillRoundRect(x + 10, y + 36, 21, 6, 4, 4);
            g.setColor(new Color(239, 70, 98));
            g.fillOval(x + 1, y + 1, 6, 6);
            g.setColor(new Color(55, 197, 139));
            g.fillOval(x + 36, y + 1, 6, 6);
            g.setColor(new Color(64, 143, 255));
            g.fillOval(x + 36, y + 34, 6, 6);
        }
    }

    static final class LetterButton extends JButton {
        final char letter;
        boolean used;

        LetterButton(char letter) {
            super(String.valueOf(letter));
            this.letter = letter;
            setFont(new Font("SansSerif", Font.BOLD, 14));
            setBorder(new EmptyBorder(8, 4, 8, 4));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setUsed(boolean used) {
            this.used = used;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = used ? new Color(54, 63, 80) : (isEnabled() ? GOLD : CARD_2);
            Color ink = used ? new Color(121, 133, 153) : (isEnabled() ? BG : TEXT);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.setColor(ink);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            String value = String.valueOf(letter);
            int x = (getWidth() - fm.stringWidth(value)) / 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(value, x, y);
            g.dispose();
        }
    }

    static final class GameActionButton extends JButton {
        final Color activeColor;
        final Color activeText;

        GameActionButton(String text, Color activeColor, Color activeText) {
            super(text);
            this.activeColor = activeColor;
            this.activeText = activeText;
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setBorder(new EmptyBorder(9, 14, 9, 14));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(isEnabled() ? activeColor : new Color(60, 70, 88));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g.setColor(isEnabled() ? activeText : MUTED);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            String value = getText();
            int x = (getWidth() - fm.stringWidth(value)) / 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(value, Math.max(6, x), y);
            g.dispose();
        }
    }

    static final class RoundedPanel extends JPanel {
        final int radius;
        final Color color;

        RoundedPanel(int radius, Color color) {
            this.radius = radius;
            this.color = color;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), radius, radius));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static JPanel card(JComponent component, int padding) {
        JPanel panel = new RoundedPanel(24, CARD);
        panel.setLayout(new BorderLayout());
        panel.setBorder(new EmptyBorder(padding, padding, padding, padding));
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    static JTextField input(String value) {
        JTextField field = new JTextField(value);
        field.setFont(new Font("SansSerif", Font.PLAIN, 15));
        field.setBackground(CARD_2);
        field.setForeground(TEXT);
        field.setCaretColor(GOLD);
        field.setBorder(new EmptyBorder(12, 14, 12, 14));
        return field;
    }

    static JLabel label(String text, int size, Color color, int style) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", style, size));
        label.setForeground(color);
        return label;
    }

    static JButton actionButton(String title, String subtitle, Color background, Color foreground) {
        JButton button = new JButton("<html><center><b>" + title + "</b><br><span style='font-size:9px'>" + subtitle + "</span></center></html>");
        button.setFont(new Font("SansSerif", Font.BOLD, 14));
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(new EmptyBorder(14, 12, 14, 12));
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
