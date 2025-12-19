import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * A non-blocking, NIO-based server for the chat application.
 * <p>
 * This server handles multiple client connections simultaneously using a single thread
 * and the Java NIO {@link Selector} mechanism. It manages:
 * <ul>
 * <li>Client connections and disconnections.</li>
 * <li>State transitions (INIT, OUTSIDE, INSIDE) for every user.</li>
 * <li>Room creation and management.</li>
 * <li>Broadcasting public messages and routing private messages.</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * // Run with default port 8000
 * java ChatServer
 * // Run with custom port
 * java ChatServer 9090
 * </pre>
 *
 * @version 1.0
 * @see ChatClient
 * @see ServerResponse
 * @see ServerCommand
 */
public class ChatServer {
    private static final int DEFAULT_PORT = 8000;
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());

    // Core Server Components
    private Selector selector;
    private ServerSocketChannel serverChannel;

    private final Map<String, ClientContext> activeUsers = new HashMap<>();

    Map<String, Room> rooms = new HashMap<>();

    /**
     * Entry point for the server application.
     * <p>
     * If no command-line arguments are provided, it defaults to listening to port {@code ChatServer.DEFAULT_PORT}.
     * @param args - [(portNumber), ... ]
     */
    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s [%2$s] %5$s%n");
        int port;

        if (!(args.length > 0)) {
            port = ChatServer.DEFAULT_PORT;
            LOGGER.fine("Using default port: " + ChatServer.DEFAULT_PORT);
        } else {
            port = Integer.parseInt(args[0]);
        }

        try {
            new ChatServer().run(port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Starts the main server loop.
     * <p>
     * Sets up the {@link ServerSocketChannel} and enters an infinite loop waiting for
     * network events (Accept, Read) via the {@link Selector}.
     * 
     * @param port The port number to bind the server to.
     * @throws IOException If the server fails to open the socket or selector.
     */
    public void run(int port) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        LOGGER.info("ChatServer started on port " + port);

        while (true) {
            if (selector.select() == 0)
                continue;

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                try {
                    if (key.isAcceptable()) {
                        handleAccept();
                    } else if (key.isReadable()) {
                        handleRead(key);
                    }
                } catch (IOException e) {
                    handleDisconnect(key);
                }
            }
        }
    }

    /**
     * Accepts a new incoming connection.
     * <p>
     * Registers the new {@link SocketChannel} with the selector for reading operations
     * and attaches a new {@link ClientContext} to manage the client's state.
     * @throws IOException If {@code serverChannel.accept()} throws an IO error.
     */
    private void handleAccept() throws IOException {
        SocketChannel sc = serverChannel.accept();
        sc.configureBlocking(false);
        SelectionKey key = sc.register(selector, SelectionKey.OP_READ);

        ClientContext client = new ClientContext(key, sc);
        key.attach(client);

        LOGGER.info("New connection: " + sc.getRemoteAddress());
    }

    /**
     * Reads data from a client channel and appends it to the client's buffer.
     * <p>
     * If a complete line (ending in newline) is found, it is passed to {@link #processCommand}.
     * @param key The selection key associated with the client.
     * @throws IOException If the {@code ClientContext.read} operation throws an IO error.
     */
    private void handleRead(SelectionKey key) throws IOException {
        ClientContext client = (ClientContext) key.attachment();

        // Check if we can read
        if (!client.read()) {
            handleDisconnect(key);
            return;
        }

        // Process all complete messages in the buffer
        while (client.hasFullMessage()) {
            String line = client.nextMessage();
            LOGGER.info("New message read: {" + line + "}");
            if (!line.isEmpty()) {
                processCommand(client, line);
            }
        }
    }

    /**
     * Removes a client from both the list of {@link #activeUsers} and {@link Room}.
     * Afterwards closes the channel and cancels the selection key.
     * @param key - Client's SelectionKey
     */
    private void handleDisconnect(SelectionKey key) {
        ClientContext client = (ClientContext) key.attachment();
        try {
            if (client.nick != null) {
                activeUsers.remove(client.nick);
            }
            if (client.room != null) {
                getRoom(client.room).removeClient(client);
            }

            key.channel().close();
            key.cancel();
            LOGGER.info("Connection closed: " + client.nick);
        } catch (IOException e) {
            // Ignore closing errors
        }
    }

    /**
     * Interprets and executes a message/command received from a client.
     * <p>
     * This method validates commands based on the client's current {@link ClientContext.State}.
     * Supported commands include {@code /nick}, {@code /join}, {@code /leave}, {@code /bye},
     * and {@code /priv}. Calls a dedicated handler for each {@link ServerCommand}.
     * 
     * @param client The client context sending the command.
     * @param line The raw text line received.
     */
    private void processCommand(ClientContext client, String line) {
        String[] parts = line.split(" ", 3);
        String cmd = (parts[0]);

        LOGGER.fine("Line: " + line);
        LOGGER.fine("Cmd: " + cmd);
        LOGGER.fine("Client state: " + client.state.toString());

        // Commands regardless of state
        switch (cmd) {
            case ServerCommand.NICK:
                handleNick(client, parts);
                return;
            case ServerCommand.BYE:
                client.sendStr(ServerResponse.BYE);
                handleDisconnect(client.key);
                return;
        }

        if (client.state == ClientContext.State.INIT) {
            client.sendStr(ServerResponse.ERROR);
            return;
        }

        if (client.state == ClientContext.State.OUTSIDE) {
            if (cmd.equals(ServerCommand.JOIN) && parts.length > 1) {
                handleJoin(client, parts[1]);
            } else if (cmd.equals(ServerCommand.PRIVATE) && parts.length > 2) {
                handlePriv(client, parts);
            } else {
                client.sendStr(ServerResponse.ERROR);
            }
            return;
        }

        if (client.state == ClientContext.State.INSIDE) {
            if (cmd.equals(ServerCommand.JOIN) && parts.length > 1) {
                handleJoin(client, parts[1]);
            } else if (cmd.equals(ServerCommand.LEAVE)) {
                handleLeave(client);
            } else if (cmd.equals(ServerCommand.PRIVATE) && parts.length > 2) {
                handlePriv(client, parts);
            } else if (line.startsWith("/")) { // if it starts with '/', but not '//', return ERROR
                // Handle message escaping
                if (line.startsWith("//")) {
                    handleMessage(client, line.substring(1));
                } else {
                    client.sendStr(ServerResponse.ERROR); // should never happen with our client
                }
            } else {
                handleMessage(client, line);
            }
        }
    }

    /** Validates and executes {@code /nick} commands. 
     * On success, updates the client's nickname and  broadcasts this change to the clients in the same Room.
     * Responds with OK if success, ERROR otherwise.
     * @param client - Client making the request.
     * @param parts - [ _ , (newNick)]
     */
    private void handleNick(ClientContext client, String[] parts) {
        LOGGER.fine("Message: " + Arrays.toString(parts));
        if (parts.length < 2) {
            LOGGER.warning("Error: not enough args");
            client.sendStr(ServerResponse.ERROR);
            return;
        }
        String newNick = parts[1];

        if (activeUsers.containsKey(newNick)) {
            LOGGER.warning("Error: username taken");
            client.sendStr(ServerResponse.ERROR);
            return;
        }

        String oldNick = client.nick;

        if (oldNick != null)
            activeUsers.remove(oldNick);
        activeUsers.put(newNick, client);

        client.setNick(newNick);
        String okResponse = ServerResponse.OK;

        switch (client.state) {
            case INIT:
                client.setStateOutside();
                client.sendStr(okResponse);
                break;
            case OUTSIDE:
                client.sendStr(okResponse);
                break;
            case INSIDE:
                client.sendStr(okResponse);
                String str = ServerResponse.NEWNICK + " " + oldNick + " " + newNick;
                getRoom(client.room).broadcast(stringToByteBuffer(str), client);
                break;
            default:
                LOGGER.severe("Bad client.state: " + client.state);
                break;
        }
    }

    /** Validates and executes {@code /join} commands. 
     * On success, updates the client's Room and broadcasts JOINED to the clients in the Room.
     * If the Room does not yet exist, it will be created.
     * Responds with OK if success.
     * @param client - client making the request.
     * @param roomName - a unique Room name.
     */ 
    private void handleJoin(ClientContext client, String roomName) {
        // If already in a room, leave it first
        LOGGER.fine("Room name: " + roomName);
        if (client.state == ClientContext.State.INSIDE) {
            Room oldRoom = getRoom(client.room);
            oldRoom.removeClient(client); // This handles the LEFT message
        }

        Room newRoom = getOrCreateRoom(roomName);
        newRoom.addClient(client); // This handles the JOINED message

        client.setRoom(roomName);
        client.setStateInside();

        String response = ServerResponse.JOINED + " " + client.nick;

        client.sendStr(ServerResponse.OK);
        newRoom.broadcast(stringToByteBuffer(response), client);
    }

    /** Validates and executes {@code /leave} commands. 
     * On success, removes the client from the Room and broadcasts LEFT to the clients in that same Room.
     * Responds with OK if success.
     * @param client - client making the request.
     */
    private void handleLeave(ClientContext client) {
        Room room = getRoom(client.room);
        room.removeClient(client);

        client.room = null;
        client.state = ClientContext.State.OUTSIDE;
        client.sendStr(ServerResponse.OK);
    }

    /** Validates and executes {@code /priv} commands. 
     * On success, sends a message to a client with the given nick.
     * If the Room does not yet exist, it will be created.
     * Responds with OK if success, ERROR otherwise.
     * @param sender - client making the request.
     * @param parts - {@code [ _ , (destClient), (message)]}
     */
    private void handlePriv(ClientContext sender, String[] parts) {
        String targetNick = parts[1];

        StringBuilder msgBody = new StringBuilder();
        for (int i = 2; i < parts.length; i++)
            msgBody.append(parts[i]).append(" ");
        String msg = msgBody.toString().trim();

        ClientContext target = activeUsers.get(targetNick);
        if (target != null) {
            sender.sendStr(ServerResponse.OK);
            target.sendStr(ServerResponse.PRIVATE + " " + sender.nick + " " + msg);
        } else {
            sender.sendStr(ServerResponse.ERROR);
        }
    }

    /** Broadcasts messages to all the clients in the Room of a given client.
     * No response is sent.
     * @param client - client making the request.
     * @param message - client's input
     */
    private void handleMessage(ClientContext client, String message) {
        Room room = getRoom(client.room);
        String formatted = ServerResponse.MESSAGE + " " + client.nick + " " + message;
        LOGGER.info(client.nick + " sent {" + message + "} to room " + room.name);
        ByteBuffer buff = stringToByteBuffer(formatted);

        client.send(buff);
        room.broadcast(buff, client);
    }

    // helpers
    static ByteBuffer stringToByteBuffer(String str) {
        str += '\n';
        return ByteBuffer.wrap(str.getBytes());
    }

    Room getOrCreateRoom(String name) {
        return rooms.computeIfAbsent(name, Room::new);
    }

    Room getRoom(String name) {
        return rooms.get(name);
    }

    /** Represents a connected client. */
    private static class ClientContext {
        enum State {
            INIT, OUTSIDE, INSIDE
        };

        final SelectionKey key;
        final SocketChannel channel;
        final ByteBuffer buffer = ByteBuffer.allocate(16384);
        final StringBuilder inputQueue = new StringBuilder();

        State state = State.INIT;
        String nick = null;
        String room = null;

        ClientContext(SelectionKey key, SocketChannel channel) {
            this.key = key;
            this.channel = channel;
        }

        public void setNick(String nick) {
            this.nick = nick;
        }

        public void setRoom(String room) {
            this.room = room;
        }

        public void setStateInside() {
            this.state = State.INSIDE;
        }

        public void setStateOutside() {
            this.state = State.OUTSIDE;
        }

        // Returns false if connection closed
        boolean read() throws IOException {
            buffer.clear();
            int bytes = channel.read(buffer);
            if (bytes == -1)
                return false;

            buffer.flip();
            inputQueue.append(StandardCharsets.UTF_8.decode(buffer));

            if (inputQueue.length() > 2048) { // limite de 2KB
                LOGGER.warning("Cliente " + nick + " excedeu tamanho de mensagem. Desconectando.");
                return false;
            }

            return true;
        }

        boolean hasFullMessage() {
            return inputQueue.indexOf("\n") != -1;
        }

        String nextMessage() {
            int idx = inputQueue.indexOf("\n");
            if (idx == -1)
                return "";
            String msg = inputQueue.substring(0, idx).trim();
            inputQueue.delete(0, idx + 1);
            return msg;
        }

        void send(ByteBuffer buff) {
            try {
                buff.rewind();
                LOGGER.info(
                        "Message to be sent to " + nick + ": {"
                                + StandardCharsets.UTF_8.decode(buff).toString().replace("\n", "\\n")
                                + "}");
                buff.rewind();

                int totalWrite = 0;
                int totalSize = buff.remaining();
                while (buff.hasRemaining()) {
                    totalWrite += channel.write(buff);
                }
                if (totalWrite != totalSize) {
                    LOGGER.warning("Couldn't empty buffer: " + StandardCharsets.UTF_8.decode(buff).toString());
                }

            } catch (IOException e) {
                LOGGER.warning("Buffer Error: " + StandardCharsets.UTF_8.decode(buff).toString());
            }
        }

        void sendStr(String msg) {
            send(ChatServer.stringToByteBuffer(msg));
        }
    }

    /** Manages a group of clients. */
    static class Room {
        String name;
        Set<ClientContext> members = new HashSet<>();

        Room(String name) {
            this.name = name;
        }

        void addClient(ClientContext client) {
            members.add(client);
        }

        void removeClient(ClientContext client) {
            if (members.remove(client)) {
                broadcast(ChatServer.stringToByteBuffer(ServerResponse.LEFT + " " + client.nick), client);
            }
        }

        void broadcast(ByteBuffer buff, ClientContext sender) {
            for (ClientContext member : members) {
                if (member != sender) {
                    member.send(buff);
                }
            }
        }
    }
}