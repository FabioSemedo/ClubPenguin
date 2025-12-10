import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

public class ChatServer {
    private static final int DEFAULT_PORT = 8000;
    
    // Core Server Components
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private final RoomManager roomManager = new RoomManager();
    
    // Lookup table for fast access to users by nickname (for /priv and uniqueness checks)
    private final Map<String, ClientContext> activeUsers = new HashMap<>();

    public static void main(String[] args) {
        int port;

        if(!(args.length > 0)){
            System.out.println(
                "[Server Main] Missing expected arguments:\nChatServer <port>\nUsing default: "
                +ChatServer.DEFAULT_PORT);

            port = ChatServer.DEFAULT_PORT;
        }else{
            port = Integer.parseInt(args[0]);
        }
        
        try {
            new ChatServer().run(port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run(int port) throws IOException {
        // 1. Setup Network
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("ChatServer started on port " + port);

        // 2. Main Loop
        while (true) {
            if (selector.select() == 0) continue;

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

    // --- Network Events ---

    private void handleAccept() throws IOException {
        SocketChannel sc = serverChannel.accept();
        sc.configureBlocking(false);
        SelectionKey key = sc.register(selector, SelectionKey.OP_READ);
        
        // Attach our Object-Oriented wrapper to the key
        ClientContext client = new ClientContext(key, sc);
        key.attach(client);
        
        System.out.println("New connection: " + sc.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        ClientContext client = (ClientContext) key.attachment();
        
        // Delegate reading to the client object
        if (!client.read()) {
            handleDisconnect(key);
            return;
        }

        // Process all complete messages in the buffer
        while (client.hasFullMessage()) {
            String line = client.nextMessage();
            if (!line.isEmpty()) {
                processCommand(client, line);
            }
        }
    }

    private void handleDisconnect(SelectionKey key) {
        ClientContext client = (ClientContext) key.attachment();
        try {
            // Cleanup Logic
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

    // --- Business Logic / Command Parsing ---

    private void processCommand(ClientContext client, String line) {
        String[] parts = line.split(" ");
        String cmd = parts[0];

        // 1. Handle Commands regardless of state
        switch (cmd) {
            case "/nick":
                handleNick(client, parts);
                return;
            case "/bye":
                client.send(ServerResponse.BYE);
                handleDisconnect(client.key);
                return;
        }

        // 2. Handle State-Specific Commands
        if (client.state == ClientContext.State.INIT) {
            client.send(ServerResponse.ERROR); // Expecting /nick
            return;
        }

        if (client.state == ClientContext.State.OUTSIDE) {
            if (cmd.equals("/join") && parts.length > 1) {
                handleJoin(client, parts[1]);
            } else if (cmd.equals("/priv") && parts.length > 2) {
                handlePriv(client, parts);
            } else {
                client.send(ServerResponse.ERROR);
            }
            return;
        }

        if (client.state == ClientContext.State.INSIDE) {
            if (cmd.equals("/join") && parts.length > 1) {
                handleJoin(client, parts[1]);
            } else if (cmd.equals("/leave")) {
                handleLeave(client);
            } else if (cmd.equals("/priv") && parts.length > 2) {
                handlePriv(client, parts);
            } else if (line.startsWith("/")) {
                // Handle message escaping
                if (line.startsWith("//")) {
                    handleMessage(client, line.substring(1));
                } else {
                    client.send(ServerResponse.ERROR);
                }
            } else {
                handleMessage(client, line);
            }
        }
    }

    // --- Command Implementations ---

    private void handleNick(ClientContext client, String[] parts) {
        if (parts.length < 2) {
            client.send(ServerResponse.ERROR);
            return;
        }
        String newNick = parts[1];
        
        if (activeUsers.containsKey(newNick)) {
            client.send(ServerResponse.ERROR);
            return;
        }

        String oldNick = client.nick;
        
        // Update Global Registry
        if (oldNick != null) activeUsers.remove(oldNick);
        activeUsers.put(newNick, client);
        
        client.nick = newNick;
        
        if (client.state == ClientContext.State.INIT) {
            client.state = ClientContext.State.OUTSIDE;
            client.send(ServerResponse.OK);
        } else if (client.state == ClientContext.State.OUTSIDE) {
            client.send(ServerResponse.OK);
        } else if (client.state == ClientContext.State.INSIDE) {
            client.send(ServerResponse.OK);
            roomManager.getRoom(client.room).broadcast(ServerResponse.NEWNICK +" " + oldNick + " " + newNick, client);
        }
    }

    private void handleJoin(ClientContext client, String roomName) {
        // If already in a room, leave it first
        if (client.state == ClientContext.State.INSIDE) {
            Room oldRoom = roomManager.getRoom(client.room);
            oldRoom.removeClient(client); // This handles the LEFT message
        }

        Room newRoom = roomManager.getOrCreateRoom(roomName);
        newRoom.addClient(client); // This handles the JOINED message
        
        client.room = roomName;
        client.state = ClientContext.State.INSIDE;
        client.send(ServerResponse.OK);
        newRoom.broadcast(ServerResponse.JOINED + " " + client.nick, client);
    }

    private void handleLeave(ClientContext client) {
        Room room = roomManager.getRoom(client.room);
        room.removeClient(client);
        
        client.room = null;
        client.state = ClientContext.State.OUTSIDE;
        client.send(ServerResponse.OK);
    }

    private void handlePriv(ClientContext sender, String[] parts) {
        String targetNick = parts[1];
        
        // Reconstruct message
        StringBuilder msgBody = new StringBuilder();
        for (int i = 2; i < parts.length; i++) msgBody.append(parts[i]).append(" ");
        String msg = msgBody.toString().trim();

        ClientContext target = activeUsers.get(targetNick);
        if (target != null) {
            sender.send(ServerResponse.OK);
            target.send(ServerResponse.PRIVATE + " " + sender.nick + " " + msg);
        } else {
            sender.send(ServerResponse.ERROR);
        }
    }

    private void handleMessage(ClientContext client, String message) {
        Room room = roomManager.getRoom(client.room);
        String formatted = ServerResponse.MESSAGE + client.nick + " " + message;
        client.send(formatted);
        room.broadcast(formatted, client);
    }

    // ====================================================================================
    // INNER CLASSES (Could be separate files)
    // ====================================================================================

    // 1. ClientContext: Encapsulates connection, buffer, and state
    static class ClientContext {
        enum State { INIT, OUTSIDE, INSIDE }
        
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

        // Returns false if connection closed
        boolean read() throws IOException {
            buffer.clear();
            int bytes = channel.read(buffer);
            if (bytes == -1) return false;

            buffer.flip();
            inputQueue.append(StandardCharsets.UTF_8.decode(buffer));
            return true;
        }

        boolean hasFullMessage() {
            return inputQueue.indexOf("\n") != -1;
        }

        String nextMessage() {
            int idx = inputQueue.indexOf("\n");
            if (idx == -1) return "";
            String msg = inputQueue.substring(0, idx).trim();
            inputQueue.delete(0, idx + 1);
            return msg;
        }

        void send(String msg) {
            try {
                channel.write(ByteBuffer.wrap((msg + "\n").getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                // Handle write error if necessary
            }
        }
        void send(ServerResponse sr){
            send(sr.toString());
        }
    }

    // 2. Room: Manages a group of clients
    static class Room {
        String name;
        Set<ClientContext> members = new HashSet<>();

        Room(String name) { this.name = name; }

        void addClient(ClientContext client) {
            members.add(client);
        }

        void removeClient(ClientContext client) {
            if (members.remove(client)) {
                broadcast("LEFT " + client.nick, client);
            }
        }

        void broadcast(String msg, ClientContext exclude) {
            for (ClientContext member : members) {
                if (member != exclude) {
                    member.send(msg);
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