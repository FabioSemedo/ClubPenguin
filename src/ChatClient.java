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
  
    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message) {
        chatArea.append(message);
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

            
        }catch (IOException e){
            System.out.println("[Constructor] error: " + e.toString());
            throw e;
        }
    }

    /// Read incoming Message
    public String readMessage(){
        try{
            String message = "";
            message = this.inputBuffer.readLine();
            return message;
        }catch (IOException e){
            System.out.println("[ReadMessage] error: "+e.toString());
            return null;
        }
    }

    /// Process incoming Message
    public void processMessage(String message){
        String[] parts = message.split(" ", 3);
        ServerResponse type = ServerResponse.fromString(parts[0]);

        switch (type) {
            case OK     : 
                //TODO consider printing somewhere else
                    printMessage("Command returned OK");
                break;
            case ERROR  : 
                //TODO consider printing somewhere else
                printMessage("Command returned ERROR");
                break;
            case MESSAGE: 
                printMessage(parts[1] + "\n" + parts[2]);
                break;
            case NEWNICK:
                printMessage(parts[1] + " changed their name to " + parts[2]); 
                break;
            case JOINED :
                printMessage(parts[1] + " has joined this chat.");
                break;
            case LEFT   : 
                printMessage(parts[1] + " has left this chat.");
                break;
            case BYE    :
                //TODO consider printing somewhere else
                printMessage("Bye");
                break;
            default:
                System.out.println("[ProcessMessage] Could not interpret response: "+ parts);
                break;
        }
    }


    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void newMessage(String message) throws IOException {
        // PREENCHER AQUI com código que envia a mensagem ao servidor
        
        //TODO limit outgoing message size to server max buffer size?
        ChatServer.MAX_BUFFER_SIZE;

        if(message.charAt(0) == '/'){
            // Check commands, else send comment
            String[] parts = message.split(" ", 2);
            ServerCommand comd = ServerCommand.fromString(parts[0]);
            switch (comd) {
                case NICK:
                case JOIN:
                case LEAVE:
                case BYE:
                case PRIVATE:
                    this.outputWriter.println(message); // has autoflush
                    break;
                default:
                    this.outputWriter.println("/"+message); // Add slash to start of the message
                    break;
            }
        }else{
            //TODO Test visual output for double new lines
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
        // PREENCHER AQUI
        final Thread serverListener = new Thread() {
            @Override
            public void run(){
                try{
                    String message = "";
                    boolean errorFlag = false;
                    while(true){
                        message = readMessage();
                        errorFlag = (message == null);
                        if(errorFlag){
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