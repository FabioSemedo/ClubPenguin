import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.logging.Logger;

public class ChatServer {
    private static final int DEFAULT_PORT = 8000;
    private static final int BUFF_CAPACITY = 16384;
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());

    // Core Server Components
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private final RoomManager roomManager = new RoomManager();
    private final ByteBuffer serverBuffer = ByteBuffer.allocate(BUFF_CAPACITY);

    // Lookup table for fast access to users by nickname (for /priv and uniqueness
    // checks)
    private final Map<String, ClientContext> activeUsers = new HashMap<>();

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s [%2$s] %5$s%n");
        int port;

        if (!(args.length > 0)) {
            port = ChatServer.DEFAULT_PORT;
            LOGGER.info("Using default port: " + ChatServer.DEFAULT_PORT);
        } else {
            port = Integer.parseInt(args[0]);
        }

        try {
            new ChatServer().run(port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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

    // Accept new clients
    private void handleAccept() throws IOException {
        SocketChannel sc = serverChannel.accept();
        sc.configureBlocking(false);
        SelectionKey key = sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

        ClientContext client = new ClientContext(key, sc);
        key.attach(client);

        LOGGER.info("New connection: " + sc.getRemoteAddress());
    }

    // Process message
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
            LOGGER.info(line);
            if (!line.isEmpty()) {
                processCommand(client, line);
            }
        }
    }

    private void handleDisconnect(SelectionKey key) {
        ClientContext client = (ClientContext) key.attachment();
        try {
            if (client.nick != null) {
                activeUsers.remove(client.nick);
            }
            if (client.room != null) {
                roomManager.getRoom(client.room).removeClient(client);
            }

            key.channel().close();
            key.cancel();
            System.out.println("Connection closed: " + client.nick);
        } catch (IOException e) {
            // Ignore closing errors
        }
    }

    // Commands

    private void processCommand(ClientContext client, String line) {
        String[] parts = line.split(" ", 3);
        String cmd = (parts[0]);

        LOGGER.info("Line: " + line);
        LOGGER.info("Cmd: " + cmd);
        LOGGER.info("Client state: " + client.state.toString());

        // 1. Handle Commands regardless of state
        switch (cmd) {
            case ServerCommand.NICK:
                handleNick(client, parts); // TODO test same source nick request
                return;
            case ServerCommand.BYE:
                client.sendStr(ServerResponse.BYE);
                handleDisconnect(client.key);
                return;
        }

        if (client.state == ClientContext.State.INIT) {
            client.sendStr(ServerResponse.ERROR + " expecting username."); // Expecting /nick
            return;
        }

        if (client.state == ClientContext.State.OUTSIDE) {
            if (cmd.equals(ServerCommand.JOIN) && parts.length > 1) {
                handleJoin(client, parts[1]);
            } else if (cmd.equals(ServerCommand.PRIVATE) && parts.length > 2) {
                handlePriv(client, parts);
            } else {
                client.sendStr(ServerResponse.ERROR + " you need to join a room.");
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
                    client.sendStr(ServerResponse.ERROR + " command not identified"); // should never happen with our
                                                                                      // client
                }
            } else {
                handleMessage(client, line);
            }
        }
    }

    private void handleNick(ClientContext client, String[] parts) {
        LOGGER.info("Message: " + Arrays.toString(parts));
        if (parts.length < 2) {
            LOGGER.info("Error: not enough args");
            client.sendStr(ServerResponse.ERROR + " not enough arguments for /nick." + Arrays.toString(parts));
            return;
        }
        String newNick = parts[1];

        if (activeUsers.containsKey(newNick)) {
            LOGGER.info("Error: username taken");
            client.sendStr(ServerResponse.ERROR + " username \"" + newNick + "\" is taken.");
            return;
        }

        String oldNick = client.nick;

        if (oldNick != null)
            activeUsers.remove(oldNick);
        activeUsers.put(newNick, client);

        client.setNick(newNick);
        String okResponse = ServerResponse.OK + " New username set: " + newNick;

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
                roomManager.getRoom(client.room).broadcast(stringToByteBuffer(str), client);
                break;
            default:
                LOGGER.info("Bad client.state: " + client.state);
                break;
        }
    }

    private void handleJoin(ClientContext client, String roomName) {
        // If already in a room, leave it first
        LOGGER.info("Room name: " + roomName);
        if (client.state == ClientContext.State.INSIDE) {
            Room oldRoom = roomManager.getRoom(client.room);
            oldRoom.removeClient(client); // This handles the LEFT message
        }

        Room newRoom = roomManager.getOrCreateRoom(roomName);
        newRoom.addClient(client); // This handles the JOINED message

        client.setRoom(roomName);
        client.setStateInside();

        String response = ServerResponse.JOINED + " " + client.nick;

        client.sendStr(ServerResponse.OK + " you joined " + roomName);
        newRoom.broadcast(stringToByteBuffer(response), client);
    }

    private void handleLeave(ClientContext client) {
        Room room = roomManager.getRoom(client.room);
        room.removeClient(client);

        client.room = null;
        client.state = ClientContext.State.OUTSIDE;
        client.sendStr(ServerResponse.OK + " you have left " + room.name);
    }

    private void handlePriv(ClientContext sender, String[] parts) {
        String targetNick = parts[1];

        StringBuilder msgBody = new StringBuilder();
        for (int i = 2; i < parts.length; i++)
            msgBody.append(parts[i]).append(" ");
        String msg = msgBody.toString().trim();

        ClientContext target = activeUsers.get(targetNick);
        if (target != null) {
            sender.sendStr(ServerResponse.OK); // enviar a propria msg?
            target.sendStr(ServerResponse.PRIVATE + " " + sender.nick + " " + msg);
        } else {
            sender.sendStr(ServerResponse.ERROR + " this username doesn't exist.");
        }
    }

    private void handleMessage(ClientContext client, String message) {
        Room room = roomManager.getRoom(client.room);
        String formatted = ServerResponse.MESSAGE + " " + client.nick + " " + message;
        System.out.printf("Room:%s client:%s\n", room.name, message);

        ByteBuffer buff = stringToByteBuffer(formatted);

        client.send(buff);// TODO consider not resending messages
        room.broadcast(buff, client);
    }

    static ByteBuffer stringToByteBuffer(String str) {
        str += '\n';
        return ByteBuffer.wrap(str.getBytes());
    }

    // ====================================================================================
    // INNER CLASSES (Could be separate files)
    // ====================================================================================

    /// Represents a connected client.
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
                LOGGER.info("Message to be sent: " + StandardCharsets.UTF_8.decode(buff).toString());
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

    // 2. Room: Manages a group of clients
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

    // 3. RoomManager: Manages Room creation and retrieval
    static class RoomManager {
        Map<String, Room> rooms = new HashMap<>();

        Room getOrCreateRoom(String name) {
            return rooms.computeIfAbsent(name, Room::new);
        }

        Room getRoom(String name) {
            return rooms.get(name);
        }
    }
}