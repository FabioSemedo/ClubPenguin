import java.io.*;
import java.net.*;
import java.util.logging.Logger;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.*;

/**
 * A Swing-based client for the chat application that communicates with a remote
 * server via TCP sockets.
 * <p>
 * This client provides a Graphical User Interface (GUI) for users to:
 * <ul>
 * <li>Connect to a ChatServer using a hostname and port.</li>
 * <li>Send public and private messages.</li>
 * <li>Execute commands such as {@code /nick}, {@code /join}, and
 * {@code /leave}.</li>
 * <li>View real-time chat history formatted with color-coded alignment.</li>
 * </ul>
 * <p>
 * The client runs on two main threads: the Swing Event Dispatch Thread (EDT)
 * for
 * UI updates and a separate listener thread for reading incoming server
 * messages.
 *
 * <p>
 * <b>Usage:</b>
 * </p>
 * 
 * <pre>
 * // Run with default localhost:8000
 * java ChatClient
 * // Run with custom host and port
 * java ChatClient 192.168.1.50 8080
 * </pre>
 * 
 * @version 1.0
 * @see ChatServer
 */
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

    /**
     * Simple server client for a text based messaging system.
     * 
     * @param hostName - server domain.
     * @param port     - port number for socket connection
     * @throws IOException - if socket could not be created
     */
    public ChatClient(String hostName, int port) throws IOException {
        try {
            this.socket = new Socket(hostName, port);
            LOGGER.info("Connecting to: " + hostName + ":" + port);

            this.outputWriter = new PrintWriter(socket.getOutputStream(), true);
            this.inputBuffer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            initUI();

        } catch (IOException e) {
            System.out.println("[Constructor] error: " + e.toString());
            throw e;
        }
    }

    /**
     * Prints a message to the Chat Text Area in the client's UI.
     * 
     * @param message - Text to be displayed
     * @param i       - Defines text alignment. 1 = left, 2 = center, 3 = right.
     */
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

    /**
     * Prints a message to the Chat Text Area with dedicated formatting.
     * 
     * @param msg       - message
     * @param alignment - Defines text alignment with a StyleConstants alignment
     *                  value.
     *                  0 = left, 1 = center, 2 = right.
     */
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

    /**
     * Initialises the UI elements the client uses to send and view messages.
     */
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

    /**
     * Read incoming Message in the {@link #inputBuffer}
     * 
     * @return The oldest message in buffer. Or null if an IO exception is thrown.
     */
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

    /**
     * Handles message interpretation. Calls
     * {@link ChatClient#printMessage(String, int)} to print result.
     * 
     * @param message - incoming server message
     * @return TRUE - if message type was recognised ({@link ServerResponse}). FALSE
     *         - otherwise.
     */
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
                printMessage("you have quit the chat.", 2);
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

    /**
     * Sends messages to the server.
     * Called when the client enters a new line in the input field and sends this
     * input to the server.
     * Appends a {@code'/'} to the start of a message if user starts the message
     * with a {@code'/'}
     * without using a {@link ServerCommand} string.
     * 
     * @param message - String received from the text field.
     */
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
                    this.outputWriter.println(message);
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

    /**
     * Closes the network socket connection.
     */
    public void closeSocket() {
        try {
            this.socket.close();
        } catch (IOException e) {
            LOGGER.severe("Failed to close socket. " + e.toString());
        }
    }

    /**
     * Disables client UI.
     */
    private void closeChat() {
        SwingUtilities.invokeLater(() -> {
            chatBox.setEditable(false);
            chatBox.setEnabled(false);

            chatBox.setText("Desconectado do servidor.");
            chatBox.setBackground(Color.DARK_GRAY);
        });
    }

    /**
     * Creates another Thread that listens for messages from the server.
     */
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

    /**
     * Entry point for the client application.
     * Instantiates and starts the client using {@link #run()}.
     * If received command-line arguments are given,
     * the client will be connected to the server
     * "{@link #DEFAULT_SERVER}:{@link #DEFAULT_PORT}"
     * <p>
     * <b>Usage:</b>
     * <p>
     * $ java ChatClient.java localhost 8000
     * 
     * @param args - [(hostName), (portNumber)]
     * @throws IOException - IO error thrown by ChatClient
     */
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
