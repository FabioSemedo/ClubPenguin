# Java Chat System

This project implements a multi-client chat system developed in Java: a server based on the multiplex model and a client with a simple graphical interface.

### Implementation

* **Multiplex Server (NIO):** Uses selectors to manage multiple clients simultaneously in a single thread.
* **Multi-threaded Client:** The client operates with two main threads to avoid interface blocking:
1. Manages user input.
2. Listens for server messages.


* **Buffering & Message Delineation:** The server implements buffers to handle TCP packet fragmentation (partial messages or multiple glued messages), processing only complete lines (terminated by `\n`).
* **Escape Mechanism:** Handling of messages starting with `/` (e.g., `//comment`) to avoid conflicts with system commands.

### Chat Features

* **Rooms:** Creation and entry into rooms via the `/join` command.
* **Private Messages:** Implementation of the `/priv` command for direct communication between users.
* **State Machine:** Permission control based on user state (`INIT`, `OUTSIDE`, `INSIDE`).

---

## Protocol

### Chat Commands

The commands available to the client depend on their current state.
| Command | Description | Example |
| --- | --- | --- |
| **/nick** | Sets or changes your nickname. | `/nick Sophia` |
| **/join** | Enters a room (creates it if it doesn't exist). | `/join salinha` |
| **/leave** | Leaves the current room and returns to the "lobby". | `/leave` |
| **/priv** | Sends a private message to a user. | `/priv Fabio Hello!` |
| **/bye** | Disconnects and closes the client. | `/bye` |

### Server Messages (Server -> Client)

The client interprets the following protocol messages sent by the server to update the interface:

| Server Response | Description | Text Displayed in UI | 
| :--- | :--- | :--- | 
| `OK` | Command success confirmation. | Success! | 
| `ERROR` | Command execution failure. | Error! Try again |
 | `MESSAGE <nick> <msg>` | Public message sent in the room. | *nick*: *msg* | 
| `PRIVATE <nick> <msg>` | Private message received. | (priv) *nick*: *msg* |
 | `NEWNICK <old> <new>` | Nickname change notification. | *old* changed their name to *new* | 
| `JOINED <nick>` | User joined the current room. | *nick* has joined this chat room. | 
| `LEFT <nick>` | User left the current room. | *nick* has left this chat room. |
 | `BYE` | Disconnection confirmation. | you have quit the chat. |

### User State Flow

The server controls what the user can do based on their state:

1. **INIT:** Connection established, but without a nickname.

* *Allowed:* Only `/nick`.

2. **OUTSIDE:** Nickname defined, but outside a room.

* *Allowed:* `/join`, `/priv`, `/nick`, `/bye`.

3. **INSIDE:** Inside a chat room.

* *Allowed:* Send public messages, `/leave`, `/priv`, `/nick`, `/bye`.

---

## How to Run

### 1. Running the Server

The server must be started first. By default, it listens on port **8000**.

```bash
# Default port (8000)
java ChatServer.java

# Custom port (e.g., 9090)
java ChatServer.java 9090


```

### 2. Running the Client

Open a new terminal (or multiple instances) to connect clients.

```bash
# Connect to localhost:8000
java ChatClient.java

# Connect to specific port
java ChatClient.java localhost 9090


```