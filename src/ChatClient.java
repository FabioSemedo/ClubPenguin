import java.io.*;
import java.net.*;
import java.util.logging.Logger;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.*;

public class ChatClient {
    private static final Logger LOGGER = Logger.getLogger(ChatClient.class.getName());

    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextPane chatArea = new JTextPane();

    private static final int DEFAULT_PORT = 8000;
    private static final String DEFAULT_SERVER = "localhost";
    private Socket socket;
    private PrintWriter outputWriter;
    private BufferedReader inputBuffer;
    private String nickname;
    private String askedNickname = "";
    private boolean lastNickCmd = false;

    // Método a usar para acrescentar uma string à caixa de texto
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

    private void appendToPane(String msg, int alignment) {
        StyledDocument doc = chatArea.getStyledDocument();

        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setAlignment(style, alignment);
        StyleConstants.setFontSize(style, 12);

        if (alignment == StyleConstants.ALIGN_RIGHT) {
            StyleConstants.setForeground(style, new Color(19, 143, 17));
        } else if (alignment == StyleConstants.ALIGN_LEFT) {
            StyleConstants.setForeground(style, new Color(17, 61, 128));
            StyleConstants.setFontSize(style, 14);
        }

        try {
            int length = doc.getLength();
            doc.insertString(length, msg + "\n", style);
            doc.setParagraphAttributes(length, msg.length(), style, false);
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public void initUI() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 350);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        chatBox.setEditable(true);
        chatBox.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chatBox.setBorder(BorderFactory.createCompoundBorder(
                chatBox.getBorder(),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        chatBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage(chatBox.getText());
                chatBox.setText("");
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent e) {
                chatBox.requestFocusInWindow();
            }
        });
    }

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
    public boolean processMessage(String message) {
        LOGGER.fine("[ProcessMessage starting]");
        String[] parts = message.split(" ", 3);
        String type = (parts[0]);

        switch (type) {
            case ServerResponse.OK:
                if (lastNickCmd) {
                    nickname = askedNickname;
                }
                printMessage("Success!\n", 2);
                LOGGER.info("Server returned OK");
                break;
            case ServerResponse.ERROR:
                printMessage("Error! Try again\n", 2);
                LOGGER.info("Server returned ERROR");
                break;
            case ServerResponse.MESSAGE:
                if (parts[1].equals(nickname)) {
                    printMessage(parts[1] + ": " + parts[2], 3); // sent message
                } else
                    printMessage(parts[1] + ": " + parts[2], 1); // received message
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
                return false;
            case ServerResponse.PRIVATE:
                printMessage("(priv) " + parts[1] + ": " + parts[2], 1);
                LOGGER.info("Server returned {" + message + "}");
                break;
            default:
                LOGGER.info("Could not interpret response: {" + message + "}");
                break;
        }

        return true;
    }

    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void sendMessage(String message) {
        if (message.equals("")) {
            LOGGER.info("Message is empty");
            return;
        }

        lastNickCmd = false;
        if (message.charAt(0) == '/') {
            // Check commands, else send message
            String[] parts = message.split(" ", 2);
            String comd = (parts[0]);
            switch (comd) {
                case ServerCommand.NICK:
                    printMessage("Changing nickname to " + parts[1] + "...", 2);
                    LOGGER.info("New outgoing message: {" + message + "}");
                    this.outputWriter.println(message); // has autoflush
                    askedNickname = parts[1];
                    lastNickCmd = true;
                    break;
                case ServerCommand.JOIN:
                    printMessage("Joining " + parts[1] + "...", 2);
                    LOGGER.info("New outgoing message: {" + message + "}");
                    this.outputWriter.println(message);
                    break;
                case ServerCommand.LEAVE:
                    printMessage("Leaving room...", 2);
                    LOGGER.info("New outgoing message: {" + message + "}");
                    this.outputWriter.println(message);
                    break;
                case ServerCommand.BYE:
                    printMessage("Exiting chat...", 2);
                    LOGGER.info("New outgoing message: {" + message + "}");
                    this.outputWriter.println(message);
                    break;
                case ServerCommand.PRIVATE:
                    String[] partsPriv = message.split(" ", 3);
                    printMessage("(priv to " + partsPriv[1] + ") " + partsPriv[2], 3);
                    printMessage("Sending private message...", 2);
                    LOGGER.info("New outgoing message: {" + message + "}");
                    this.outputWriter.println(message);
                    break;
                default: // starts with '/' but it's not a command -> adds /
                    LOGGER.info("New outgoing message: {" + "/" + message + "}");
                    this.outputWriter.println("/" + message); // add slash to start of the message: /add >> //add
                    break;
            }
        } else {
            // message to server
            this.outputWriter.println(message);
            LOGGER.info("New outgoing message: {" + message + "}");
        }
    }

    public void closeSocket() {
        try {
            this.socket.close();
        } catch (IOException e) {
            LOGGER.severe("Failed to close socket. " + e.toString());
        }
    }

    private void closeChat() {
        SwingUtilities.invokeLater(() -> {
            chatBox.setEditable(false);
            chatBox.setEnabled(false);

            chatBox.setText("Desconectado do servidor.");
            chatBox.setBackground(Color.DARK_GRAY);
        });
    }

    public void run() {
        LOGGER.fine("ServerListener start");

        final Thread serverListener = new Thread() {
            @Override
            public void run() {
                LOGGER.info("ServerListener Thread running");
                try {
                    String message = "";
                    while (true) {
                        message = readMessage();
                        if (message == null) {
                            appendToPane("Connection was lost", 1);
                            break;
                        } else {
                            if (!processMessage(message))
                                break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warning("Error: " + e.toString());
                } finally {
                    LOGGER.info("ServerListener end");
                    closeSocket();
                    closeChat();
                }

            }
        };

        serverListener.start();
    }

    // Instancia o ChatClient e arranca-o invocando o seu método run()
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
