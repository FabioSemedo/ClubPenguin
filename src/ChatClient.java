import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class ChatClient {

    // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // --- Fim das variáveis relacionadas coma interface gráfica

    // Se for necessário adicionar variáveis ao objecto ChatClient, devem
    // ser colocadas aqui
    private static final int DEFAULT_PORT = 8000;
    private static final String DEFAULT_SERVER = "localhost";
    private Socket socket;
    private PrintWriter outputWriter;
    private BufferedReader inputBuffer;

    enum State { INIT, OUTSIDE, INSIDE };
    State state;
  
    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message) {
        chatArea.append(message + "\n");
        //TODO
        System.out.println(message);
    }

    public void initUI(){
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
        chatBox.setEditable(true);
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
        // Inicialização da interface gráfica --- * NÃO MODIFICAR *
        initUI();
        // --- Fim da inicialização da interface gráfica

        // Se for necessário adicionar código de inicialização ao
        // construtor, deve ser colocado aqui

        try{
            this.socket = new Socket(hostName, port);
            System.out.println("Connnecting to: " + hostName+ ":"+port);

            this.outputWriter = new PrintWriter(socket.getOutputStream(), true);
            this.inputBuffer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // TODO state based error messages. Example: "Create user name"
            this.state = State.INIT;
            
        }catch (IOException e){
            System.out.println("[Constructor] error: " + e.toString());
            throw e;
        }
    }

    /// Read incoming Message
    public String readMessage(){
        printMessage("[Reading Message...]");
        try{
            String message = "";
            message = this.inputBuffer.readLine();
            printMessage("["+message+"]");
            return message;
        }catch (Exception e){
            System.out.println("[ReadMessage] error: "+e.toString());
            return null;
        }
    }

    /// Process incoming Message
    public void processMessage(String message){
        printMessage("[ProcessMessage start]");
        String[] parts = message.split(" ", 3);
        String type = (parts[0]);

        switch (type) {
            case ServerResponse.OK     : 
                //TODO consider printing somewhere else
                printMessage("[Command returned OK]");
                System.out.println("[ProcessMessage] Server returned OK");
                break;
            case ServerResponse.ERROR  : 
                printMessage("[Could not send message] {" + message +"}");
                System.out.println("[ProcessMessage] Server returned ERROR");
                break;
            case ServerResponse.MESSAGE: 
                printMessage(parts[1] + "\n" + parts[2]);
                System.out.println("[ProcessMessage] Server returned MESSAGE\n{" + message+"}");
                break;
            case ServerResponse.NEWNICK:
                printMessage(parts[1] + " changed their name to " + parts[2]); 
                System.out.println("[ProcessMessage] Server returned NEWNICK");
                break;
            case ServerResponse.JOINED :
                printMessage(parts[1] + " has joined this chat.");
                System.out.println("[ProcessMessage] Server returned JOINED");
                break;
            case ServerResponse.LEFT   : 
                printMessage(parts[1] + " has left this chat.");
                System.out.println("[ProcessMessage] Server returned LEFT");
                break;
            case ServerResponse.BYE    :
                //TODO consider printing somewhere else
                printMessage("You have left the chat room.");
                System.out.println("[ProcessMessage] Server returned BYE");
                break;
            default:
                System.out.println("[ProcessMessage] Could not interpret response: "+ parts);
                break;
        }
    }


    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void sendMessage(String message) throws IOException {
        // PREENCHER AQUI com código que envia a mensagem ao servidor
        System.out.println("[SendMessage] New outgoing message: \n{" +message +"}");
        //TODO limit outgoing message size to server max buffer size?
        // ChatServer.MAX_BUFFER_SIZE; // 16384

        if(message.charAt(0) == '/'){
            // Check commands, else send comment
            String[] parts = message.split(" ", 2);
            String comd = (parts[0]);
            switch (comd) {
                case ServerCommand.NICK:
                case ServerCommand.JOIN:
                case ServerCommand.LEAVE:
                case ServerCommand.BYE:
                case ServerCommand.PRIVATE:
                    this.outputWriter.println(message); // has autoflush
                    break;
                default:
                    this.outputWriter.println("/"+message); // Add slash to start of the message: /add >> //add
                    break;
            }
        }else{
            //TODO Test visual output for double new lines
            //Send message to server
            this.outputWriter.println(message); // has autoflush
        }
    }

    public void closeSocket(){
        try{
            this.socket.close();
        }catch (IOException e){
            System.out.println("[CloseSocket] failed to close socket. " + e.toString());
        }
    }

    
    // Método principal do objecto
    public void run() throws IOException {
        printMessage("[ServerListener start]");
        // PREENCHER AQUI
        final Thread serverListener = new Thread() {
            @Override
            public void run(){
                printMessage("[ServerListener Thread running]");
                try{
                    String message = "";
                    while(true){
                        message = readMessage();
                        if(message == null){
                            printMessage("[message is null]");
                            break;
                        }else{
                            processMessage(message);
                        }
                    }
                }catch(Exception e){
                    System.out.println("[ServerListener] error: "+e.toString());
                }finally {
                    closeSocket();
                }
            }
        };

        serverListener.start();
        printMessage("[ServerListener end]");
    }
    
    // Instancia o ChatClient e arranca-o invocando o seu método run()
    // * NÃO MODIFICAR *
    public static void main(String[] args) throws IOException {
        if(args.length < 2){
            System.out.println(
                "[Main] Missing expected arguments:\nChatClient <hostName> <port>\nUsing default: "
                +ChatClient.DEFAULT_SERVER+":"+ ChatClient.DEFAULT_PORT);

            ChatClient client = new ChatClient(ChatClient.DEFAULT_SERVER, ChatClient.DEFAULT_PORT);
            client.run();
        }else {
            ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
            client.run();
        }
    }

}