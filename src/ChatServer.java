import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

public class ChatServer {

    // Context to track state for each connected client
    static class ClientContext {
        enum State { INIT, OUTSIDE, INSIDE }
        
        public State state = State.INIT;
        public String nickname = null;
        public String room = null;
        // Buffer to accumulate partial data until a full line is received
        public StringBuilder buffer = new StringBuilder();
    }

    // A pre-allocated buffer for data transfer
    static private final ByteBuffer readBuffer = ByteBuffer.allocate(16384);
    static private final Charset charset = StandardCharsets.UTF_8;
    static private final CharsetDecoder decoder = charset.newDecoder();

    static public void main(String args[]) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java ChatServer <port>");
            return;
        }

        int port = Integer.parseInt(args[0]);

        try {
            // Setup ServerSocketChannel
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ServerSocket ss = ssc.socket();
            ss.bind(new InetSocketAddress(port));

            // Setup Selector
            Selector selector = Selector.open();
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("ChatServer started on port " + port);

            while (true) {
                if (selector.select() == 0) continue;

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (key.isAcceptable()) {
                        // Accept new connection
                        Socket s = ss.accept();
                        System.out.println("New connection: " + s);
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking(false);
                        
                        // Register for reading and attach a new ClientContext
                        SelectionKey newKey = sc.register(selector, SelectionKey.OP_READ);
                        newKey.attach(new ClientContext());

                    } else if (key.isReadable()) {
                        SocketChannel sc = null;
                        try {
                            sc = (SocketChannel) key.channel();
                            processInput(sc, key, selector);
                        } catch (IOException ie) {
                            // Handle abrupt disconnection
                            closeConnection(key, selector);
                        }
                    }
                }
            }
        } catch (IOException ie) {
            System.err.println(ie);
        }
    }

    // Read data, reconstruct lines, and process commands
    static private void processInput(SocketChannel sc, SelectionKey key, Selector selector) throws IOException {
        readBuffer.clear();
        int numBytes = sc.read(readBuffer);

        if (numBytes == -1) {
            closeConnection(key, selector);
            return;
        }

        readBuffer.flip();
        String fragment = decoder.decode(readBuffer).toString();
        ClientContext ctx = (ClientContext) key.attachment();
        ctx.buffer.append(fragment);

        // Process line by line
        while (true) {
            int newlineIdx = ctx.buffer.indexOf("\n");
            if (newlineIdx == -1) break;

            String line = ctx.buffer.substring(0, newlineIdx).trim();
            ctx.buffer.delete(0, newlineIdx + 1);

            if (!line.isEmpty()) {
                handleCommand(key, ctx, line, selector);
            }
        }
    }

    // Core logic for the protocol state machine
    static private void handleCommand(SelectionKey key, ClientContext ctx, String line, Selector selector) throws IOException {
        String[] parts = line.split(" ");
        String cmd = parts[0];

        // --- STATE: INIT ---
        if (ctx.state == ClientContext.State.INIT) {
            if (cmd.equals("/nick") && parts.length > 1) {
                String nick = parts[1];
                if (isNickAvailable(selector, nick)) {
                    ctx.nickname = nick;
                    ctx.state = ClientContext.State.OUTSIDE;
                    send(key, "OK");
                } else {
                    send(key, "ERROR");
                }
            } else if (cmd.equals("/bye")) {
                send(key, "BYE");
                closeConnection(key, selector);
            } else {
                send(key, "ERROR");
            }
            return;
        }

        // --- STATE: OUTSIDE ---
        if (ctx.state == ClientContext.State.OUTSIDE) {
            if (cmd.equals("/nick") && parts.length > 1) {
                String nick = parts[1];
                if (isNickAvailable(selector, nick)) {
                    ctx.nickname = nick;
                    send(key, "OK");
                } else {
                    send(key, "ERROR");
                }
            } else if (cmd.equals("/join") && parts.length > 1) {
                String room = parts[1];
                ctx.room = room;
                ctx.state = ClientContext.State.INSIDE;
                send(key, "OK");
                broadcast(selector, room, "JOINED " + ctx.nickname, key);
            } else if (cmd.equals("/priv") && parts.length > 2) {
                handlePriv(selector, key, ctx, parts);
            } else if (cmd.equals("/bye")) {
                send(key, "BYE");
                closeConnection(key, selector);
            } else {
                send(key, "ERROR");
            }
            return;
        }

        // --- STATE: INSIDE ---
        if (ctx.state == ClientContext.State.INSIDE) {
            if (cmd.equals("/nick") && parts.length > 1) {
                String newNick = parts[1];
                if (isNickAvailable(selector, newNick)) {
                    String oldNick = ctx.nickname;
                    ctx.nickname = newNick;
                    send(key, "OK");
                    broadcast(selector, ctx.room, "NEWNICK " + oldNick + " " + newNick, key);
                } else {
                    send(key, "ERROR");
                }
            } else if (cmd.equals("/join") && parts.length > 1) {
                String oldRoom = ctx.room;
                String newRoom = parts[1];
                broadcast(selector, oldRoom, "LEFT " + ctx.nickname, key);
                ctx.room = newRoom;
                send(key, "OK");
                broadcast(selector, newRoom, "JOINED " + ctx.nickname, key);
            } else if (cmd.equals("/leave")) {
                String room = ctx.room;
                ctx.room = null;
                ctx.state = ClientContext.State.OUTSIDE;
                send(key, "OK");
                broadcast(selector, room, "LEFT " + ctx.nickname, key);
            } else if (cmd.equals("/bye")) {
                broadcast(selector, ctx.room, "LEFT " + ctx.nickname, key);
                send(key, "BYE");
                closeConnection(key, selector);
            } else if (cmd.equals("/priv") && parts.length > 2) {
                handlePriv(selector, key, ctx, parts);
            } else {
                // Handling Messages
                String message = line;
                if (message.startsWith("/")) {
                    if (message.startsWith("//")) {
                        // Unescape: "//hello" -> "/hello"
                        message = message.substring(1);
                    } else {
                        send(key, "ERROR");
                        return;
                    }
                }
                String fullMsg = "MESSAGE " + ctx.nickname + " " + message;
                // Send to user (for consistency with spec) and broadcast
                send(key, fullMsg);
                broadcast(selector, ctx.room, fullMsg, key);
            }
        }
    }

    // Helper to handle private messages
    static private void handlePriv(Selector selector, SelectionKey senderKey, ClientContext senderCtx, String[] parts) throws IOException {
        String targetNick = parts[1];
        String msgContent = "";
        for (int i = 2; i < parts.length; i++) msgContent += parts[i] + " ";
        
        SelectionKey targetKey = null;
        for (SelectionKey key : selector.keys()) {
            ClientContext ctx = (ClientContext) key.attachment();
            if (ctx != null && targetNick.equals(ctx.nickname)) {
                targetKey = key;
                break;
            }
        }

        if (targetKey != null) {
            send(senderKey, "OK");
            send(targetKey, "PRIVATE " + senderCtx.nickname + " " + msgContent.trim());
        } else {
            send(senderKey, "ERROR");
        }
    }

    static private void send(SelectionKey key, String msg) throws IOException {
        if (key.isValid() && key.channel().isOpen()) {
            SocketChannel sc = (SocketChannel) key.channel();
            sc.write(ByteBuffer.wrap((msg + "\n").getBytes(charset)));
        }
    }

    static private void broadcast(Selector selector, String room, String msg, SelectionKey exceptKey) throws IOException {
        for (SelectionKey key : selector.keys()) {
            ClientContext ctx = (ClientContext) key.attachment();
            if (key.isValid() && ctx != null && ctx.state == ClientContext.State.INSIDE && room.equals(ctx.room)) {
                if (key != exceptKey) {
                    send(key, msg);
                }
            }
        }
    }

    static private boolean isNickAvailable(Selector selector, String nick) {
        for (SelectionKey key : selector.keys()) {
            ClientContext ctx = (ClientContext) key.attachment();
            if (ctx != null && nick.equals(ctx.nickname)) return false;
        }
        return true;
    }

    static private void closeConnection(SelectionKey key, Selector selector) {
        try {
            ClientContext ctx = (ClientContext) key.attachment();
            // If user disconnects abruptly while inside a room, notify others
            if (ctx != null && ctx.state == ClientContext.State.INSIDE) {
                broadcast(selector, ctx.room, "LEFT " + ctx.nickname, key);
            }
            key.channel().close();
            key.cancel();
        } catch (IOException ex) {
            // ignore
        }
    }
}