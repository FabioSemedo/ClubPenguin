import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class ChatClient {

    // GUI Variables --- * DO NOT MODIFY *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // --- End GUI Variables

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    public void printMessage(final String message) {
        chatArea.append(message);
    }

    public ChatClient(String server, int port) throws IOException {
        // GUI Initialization --- * DO NOT MODIFY *
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
        chatBox.setEditable(true);
        chatBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    newMessage(chatBox.getText());
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
        // --- End GUI Initialization

        // Connect to server
        socket = new Socket(server, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    public void newMessage(String message) throws IOException {
        // Implements the escape mechanism required by the protocol
        // If message starts with '/' but is NOT a command, we must escape it with another '/'
        String toSend = message;
        if (message.startsWith("/")) {
            String[] parts = message.split(" ");
            String cmd = parts[0];
            boolean isCommand = cmd.equals("/nick") || cmd.equals("/join") || 
                                cmd.equals("/leave") || cmd.equals("/bye") || 
                                cmd.equals("/priv");
            
            if (!isCommand) {
                toSend = "/" + message;
            }
        }
        out.println(toSend);
    }

    public void run() throws IOException {
        // Main thread manages the GUI (started in constructor).
        // This method starts the reading thread.
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final String msg = line;
                // Use invokeLater to update GUI from a different thread
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        processIncomingMessage(msg);
                    }
                });
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> printMessage("Connection lost.\n"));
        } finally {
            socket.close();
            System.exit(0);
        }
    }

    // Formats the raw protocol messages into friendly text
    private void processIncomingMessage(String line) {
        String[] parts = line.split(" ", 3);
        String type = parts[0];

        switch (type) {
            case "OK":
                // printMessage("Command successful.\n"); // Optional: reduce clutter
                break;
            case "ERROR":
                printMessage("Error executing command.\n");
                break;
            case "MESSAGE": // MESSAGE <nick> <msg>
                if (parts.length >= 3) {
                    printMessage(parts[1] + ": " + parts[2] + "\n");
                }
                break;
            case "NEWNICK": // NEWNICK <old> <new>
                if (parts.length >= 3) {
                    printMessage(parts[1] + " is now known as " + parts[2] + "\n");
                }
                break;
            case "JOINED": // JOINED <nick>
                if (parts.length >= 2) {
                    printMessage(parts[1] + " joined the room.\n");
                }
                break;
            case "LEFT": // LEFT <nick>
                if (parts.length >= 2) {
                    printMessage(parts[1] + " left the room.\n");
                }
                break;
            case "BYE":
                printMessage("Goodbye!\n");
                break;
            case "PRIVATE": // PRIVATE <sender> <msg>
                if (parts.length >= 3) {
                    printMessage("(Private) " + parts[1] + ": " + parts[2] + "\n");
                }
                break;
            default:
                printMessage(line + "\n");
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: java ChatClient <server> <port>");
            return;
        }
        ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
        client.run();
    }
}