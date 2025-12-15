import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.logging.Logger;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.border.EmptyBorder;

public class ChatClient {
    private static final Logger LOGGER = Logger.getLogger(ChatClient.class.getName());

    // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    // private JTextArea chatArea = new JTextArea();
    private JTextPane chatArea = new JTextPane();
    // --- Fim das variáveis relacionadas coma interface gráfica

    // Se for necessário adicionar variáveis ao objecto ChatClient, devem
    // ser colocadas aqui
    private static final int DEFAULT_PORT = 8000;
    private static final String DEFAULT_SERVER = "localhost";
    private Socket socket;
    private PrintWriter outputWriter;
    private BufferedReader inputBuffer;

    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message, int i) {
        switch (i) {
            case 1:
                appendToPane(message + "\n", StyleConstants.ALIGN_LEFT);
                break;
            case 2:
                appendToPane(message + "\n", StyleConstants.ALIGN_CENTER);
                break;
            case 3:
                appendToPane(message + "\n", StyleConstants.ALIGN_RIGHT);
                break;
            default:
                break;
        }
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // Método para adicionar mensagem com alinhamento (Esquerda, Direita ou Centro)
    private void appendToPane(String msg, int alignment) {
        StyledDocument doc = chatArea.getStyledDocument();

        // Define o estilo (atributos)
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setAlignment(style, alignment);
        StyleConstants.setFontSize(style, 12);

        // Opcional: Cores diferentes dependendo do lado (visual WhatsApp)
        if (alignment == StyleConstants.ALIGN_RIGHT) {
            StyleConstants.setForeground(style, new Color(19, 143, 17)); // Verde para mim
            StyleConstants.setFontSize(style, 14);
        } else if (alignment == StyleConstants.ALIGN_LEFT) {
            StyleConstants.setForeground(style, new Color(17, 61, 128)); // Branco para outros
            StyleConstants.setFontSize(style, 14);
        }

        // Define o tamanho da fonte (opcional, pois já definimos no construtor)

        try {
            int length = doc.getLength();
            // Insere o texto no final
            doc.insertString(length, msg + "\n", style);
            // Aplica o alinhamento ao parágrafo recém-criado
            doc.setParagraphAttributes(length, msg.length(), style, false);

            // Auto-scroll
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public void initUI() {
        // Inicialização da interface gráfica --- * NÃO MODIFICAR *
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        chatArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        chatBox.setEditable(true);
        chatBox.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chatBox.setBorder(BorderFactory.createCompoundBorder(
                chatBox.getBorder(),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        chatBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    sendMessage(chatBox.getText());
                } catch (IOException ex) {
                } finally {
                    chatBox.setText("");
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent e) {
                chatBox.requestFocusInWindow();
            }
        });
        // --- Fim da inicialização da interface gráfica
    }

    // Construtor
    public ChatClient(String hostName, int port) throws IOException {
        try {
            this.socket = new Socket(hostName, port);
            LOGGER.info("Connnecting to: " + hostName + ":" + port);

            this.outputWriter = new PrintWriter(socket.getOutputStream(), true);
            this.inputBuffer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            initUI();

        } catch (IOException e) {
            System.out.println("[Constructor] error: " + e.toString());
            throw e;
        }
    }

    /// Read incoming Message
    public String readMessage() {
        LOGGER.info("Waiting message...");
        try {
            String message = "";
            message = this.inputBuffer.readLine();

            LOGGER.info("Message received: {" + message + "}");
            return message;
        } catch (Exception e) {
            LOGGER.warning("Error: " + e.toString());
            return null;
        }
    }

    /// Process incoming Message
    public void processMessage(String message) {
        LOGGER.fine("[ProcessMessage starting]");
        String[] parts = message.split(" ", 3);
        String type = (parts[0]);

        switch (type) {
            case ServerResponse.OK:
                printMessage("Success!\n", 2);
                LOGGER.info("Server returned OK");
                break;
            case ServerResponse.ERROR:
                printMessage("Error! Try again\n", 2);
                LOGGER.info("Server returned ERROR");
                break;
            case ServerResponse.MESSAGE:
                printMessage(parts[1] + ": " + parts[2], 1);
                LOGGER.info("Server returned {" + message + "}");
                break;
            case ServerResponse.NEWNICK:
                printMessage(parts[1] + " changed their name to " + parts[2], 2);
                LOGGER.info("Server returned NEWNICK");
                break;
            case ServerResponse.JOINED:
                printMessage(parts[1] + " has joined this chat room.", 2);
                LOGGER.info("Server returned JOINED");
                break;
            case ServerResponse.LEFT:
                printMessage(parts[1] + " has left this chat room.", 2);
                LOGGER.info("Server returned LEFT");
                break;
            case ServerResponse.BYE:
                // é pra fechar a interface?
                printMessage("You have quit the chat.", 2);
                LOGGER.info("Server returned BYE");
                break;
            case ServerResponse.PRIVATE:
                printMessage("(priv) " + parts[1] + ": " + parts[2], 1);
                LOGGER.info("Server returned {" + message + "}");
                break;
            default:
                LOGGER.info("Could not interpret response: {" + message + "}");
                break;
        }
    }

    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void sendMessage(String message) throws IOException {
        // PREENCHER AQUI com código que envia a mensagem ao servidor
        if (message.equals("")) {
            LOGGER.info("Message is empty");
            return;
        }
        if (message.charAt(0) == '/') {
            // Check commands, else send comment
            String[] parts = message.split(" ", 2);
            String comd = (parts[0]);
            switch (comd) {
                case ServerCommand.NICK:
                    printMessage("Changing nickname to " + parts[1] + "...", 2);
                    this.outputWriter.println(message); // has autoflush
                    LOGGER.info("New outgoing message: {" + message + "}");
                    break;
                case ServerCommand.JOIN:
                    printMessage("Joining " + parts[1] + "...", 2);
                    this.outputWriter.println(message); // has autoflush
                    LOGGER.info("New outgoing message: {" + message + "}");
                    break;
                case ServerCommand.LEAVE:
                    printMessage("Leaving room...", 2);
                    this.outputWriter.println(message); // has autoflush
                    LOGGER.info("New outgoing message: {" + message + "}");
                    break;
                case ServerCommand.BYE:
                    printMessage("Exiting chat...", 2);
                    this.outputWriter.println(message); // has autoflush
                    LOGGER.info("New outgoing message: {" + message + "}");
                    break;
                case ServerCommand.PRIVATE:
                    this.outputWriter.println(message); // has autoflush
                    String[] partsPriv = message.split(" ", 3);
                    printMessage("(priv to " + partsPriv[1] + ") " + partsPriv[2], 3);
                    printMessage("Sending private message...", 2);
                    LOGGER.info("New outgoing message: {" + message + "}");
                    break;
                default:
                    this.outputWriter.println("/" + message); // Add slash to start of the message: /add >> //add
                    LOGGER.info("New outgoing message: {" + "/" + message + "}");
                    break;
            }
        } else {
            // TODO Test visual output for double new lines
            // Send message to server
            printMessage(message, 3);
            this.outputWriter.println(message); // has autoflush
            LOGGER.info("New outgoing message: {" + message + "}");
        }
    }

    public void closeSocket() {
        try {
            this.socket.close();
        } catch (IOException e) {
            LOGGER.severe("[CloseSocket] failed to close socket. " + e.toString());
        }
    }

    // Método principal do objecto
    public void run() {
        LOGGER.fine("ServerListener start");

        final Thread serverListener = new Thread() {
            @Override
            public void run() {
                LOGGER.info("[ServerListener Thread running]");
                try {
                    String message = "";
                    while (true) {
                        message = readMessage();
                        if (message.equals("")) {
                            LOGGER.info("Message is empty");
                        } else {
                            processMessage(message);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warning("Error: " + e.toString());
                } finally {
                    LOGGER.info("ServerListener end"); // TODO: deve fechar tudo?
                    closeSocket();
                }
            }
        };

        serverListener.start();
    }

    // Instancia o ChatClient e arranca-o invocando o seu método run()
    // * NÃO MODIFICAR *
    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s [%2$s] %5$s%n");

        if (args.length < 2) {
            LOGGER.info("Using default port: " + ChatClient.DEFAULT_SERVER + ":" + ChatClient.DEFAULT_PORT);
            ChatClient client = new ChatClient(ChatClient.DEFAULT_SERVER, ChatClient.DEFAULT_PORT);
            client.run();
        } else {
            ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
            client.run();
        }
    }

}
