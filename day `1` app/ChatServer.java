import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {
    private static final int PORT = 8080;
    private final ServerSocket serverSocket;
    private final AtomicInteger clientIdCounter = new AtomicInteger(1);
    
    // Thread-safe collections
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, PrivateChatSession> privateChats = new ConcurrentHashMap<>();
    
    public ChatServer() throws IOException {
        this.serverSocket = new ServerSocket(PORT);
        // Create default lobby room
        rooms.put("Lobby", new ChatRoom("Lobby"));
        System.out.println("Chat Server started on port " + PORT + " - Binding to all network interfaces");
    }
    
    public void start() {
        System.out.println("Server ready for connections...");
        
        while (!serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                int clientId = clientIdCounter.getAndIncrement();
                ClientHandler clientHandler = new ClientHandler(clientSocket, clientId, this);
                clients.put(clientId, clientHandler);
                new Thread(clientHandler).start();
                
                System.out.println("Client #" + clientId + " connected from " + 
                    clientSocket.getInetAddress().getHostAddress());
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.out.println("Server accept error: " + e.getMessage());
                }
            }
        }
    }
    
    // Room management
    public synchronized boolean createRoom(String roomName, ClientHandler creator) {
        if (rooms.containsKey(roomName)) {
            return false;
        }
        ChatRoom newRoom = new ChatRoom(roomName);
        rooms.put(roomName, newRoom);
        return true;
    }
    
    public synchronized boolean joinRoom(String roomName, ClientHandler client) {
        ChatRoom room = rooms.get(roomName);
        if (room != null) {
            // Leave current room first
            if (client.getCurrentRoom() != null) {
                leaveRoom(client);
            }
            room.addClient(client);
            client.setCurrentRoom(room);
            return true;
        }
        return false;
    }
    
    public synchronized void leaveRoom(ClientHandler client) {
        ChatRoom currentRoom = client.getCurrentRoom();
        if (currentRoom != null) {
            currentRoom.removeClient(client);
            client.setCurrentRoom(null);
            
            // Auto-delete empty rooms (except Lobby)
            if (currentRoom.getClients().isEmpty() && !currentRoom.getName().equals("Lobby")) {
                rooms.remove(currentRoom.getName());
            }
        }
    }
    
    // Private chat management
    public synchronized boolean requestPrivateChat(ClientHandler requester, String targetUsername) {
        ClientHandler target = findClientByUsername(targetUsername);
        if (target == null || target == requester) {
            return false;
        }
        
        String sessionKey = generatePrivateSessionKey(requester.getUsername(), target.getUsername());
        if (privateChats.containsKey(sessionKey)) {
            return false; // Chat already exists
        }
        
        PrivateChatSession session = new PrivateChatSession(requester, target);
        privateChats.put(sessionKey, session);
        
        // Notify target user
        target.sendPrivateRequest(requester.getUsername());
        return true;
    }
    
    public synchronized void acceptPrivateChat(ClientHandler acceptor, String requesterUsername) {
        String sessionKey = generatePrivateSessionKey(requesterUsername, acceptor.getUsername());
        PrivateChatSession session = privateChats.get(sessionKey);
        
        if (session != null && session.getTarget() == acceptor) {
            session.activate();
            
            // Move both users to private mode
            session.getRequester().setPrivateChatSession(session);
            session.getTarget().setPrivateChatSession(session);
            
            // Leave their current rooms
            leaveRoom(session.getRequester());
            leaveRoom(session.getTarget());
            
            // Notify both users
            session.broadcastPrivateMessage("[SERVER] Private chat started!", true);
        }
    }
    
    public synchronized void declinePrivateChat(ClientHandler decliner, String requesterUsername) {
        String sessionKey = generatePrivateSessionKey(requesterUsername, decliner.getUsername());
        PrivateChatSession session = privateChats.remove(sessionKey);
        
        if (session != null) {
            session.getRequester().sendSystemMessage(
                "Private chat request to " + decliner.getUsername() + " was declined."
            );
        }
    }
    
    public synchronized void endPrivateChat(PrivateChatSession session) {
        String sessionKey = generatePrivateSessionKey(
            session.getRequester().getUsername(), 
            session.getTarget().getUsername()
        );
        privateChats.remove(sessionKey);
        
        // Return users to Lobby
        joinRoom("Lobby", session.getRequester());
        joinRoom("Lobby", session.getTarget());
        
        session.getRequester().setPrivateChatSession(null);
        session.getTarget().setPrivateChatSession(null);
    }
    
    // Utility methods
    private String generatePrivateSessionKey(String user1, String user2) {
        String[] users = {user1, user2};
        Arrays.sort(users);
        return users[0] + "_" + users[1];
    }
    
    private ClientHandler findClientByUsername(String username) {
        return clients.values().stream()
            .filter(client -> client.getUsername() != null && 
                    client.getUsername().equalsIgnoreCase(username))
            .findFirst()
            .orElse(null);
    }
    
    public List<String> getRoomNames() {
        return new ArrayList<>(rooms.keySet());
    }
    
    public void removeClient(ClientHandler client) {
        clients.remove(client.getClientId());
        leaveRoom(client);
        
        // Clean up any private chats
        if (client.getPrivateChatSession() != null) {
            endPrivateChat(client.getPrivateChatSession());
        }
    }
    
    public static void main(String[] args) {
        try {
            ChatServer server = new ChatServer();
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            System.exit(1);
        }
    }
}

class ChatRoom {
    private final String name;
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Map<ClientHandler, Long> typingUsers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService typingCleanup = Executors.newScheduledThreadPool(1);
    
    public ChatRoom(String name) {
        this.name = name;
        // Clean up typing indicators every 3 seconds
        typingCleanup.scheduleAtFixedRate(this::cleanupTypingIndicators, 3, 3, TimeUnit.SECONDS);
    }
    
    public String getName() { return name; }
    
    public Set<ClientHandler> getClients() { return clients; }
    
    public void addClient(ClientHandler client) {
        clients.add(client);
        broadcastSystemMessage(client.getUsername() + " joined the room");
    }
    
    public void removeClient(ClientHandler client) {
        clients.remove(client);
        typingUsers.remove(client);
        broadcastSystemMessage(client.getUsername() + " left the room");
    }
    
    public void broadcastMessage(String message, ClientHandler sender) {
        String formattedMessage = String.format("[%s] [%s] %-15s: %s",
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            name,
            sender.getUsername(),
            message
        );
        
        for (ClientHandler client : clients) {
            if (client != sender) {
                client.sendMessage(formattedMessage);
            } else {
                // Send own message in green
                client.sendOwnMessage(formattedMessage);
            }
        }
    }
    
    public void broadcastSystemMessage(String message) {
        String formattedMessage = String.format("\u001B[36m[%s] [SERVER] %s\u001B[0m",
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            message
        );
        
        for (ClientHandler client : clients) {
            client.sendMessage(formattedMessage);
        }
    }
    
    public void setUserTyping(ClientHandler client) {
        typingUsers.put(client, System.currentTimeMillis());
        broadcastTypingIndicator();
    }
    
    public void setUserStoppedTyping(ClientHandler client) {
        typingUsers.remove(client);
        broadcastTypingIndicator();
    }
    
    private void broadcastTypingIndicator() {
        if (typingUsers.isEmpty()) {
            // Clear typing indicator
            for (ClientHandler client : clients) {
                client.sendTypingIndicator("");
            }
        } else {
            String typingText = String.join(", ", 
                typingUsers.keySet().stream()
                    .map(ClientHandler::getUsername)
                    .toArray(String[]::new)
            ) + (typingUsers.size() == 1 ? " is typing..." : " are typing...");
            
            for (ClientHandler client : clients) {
                client.sendTypingIndicator(typingText);
            }
        }
    }
    
    private void cleanupTypingIndicators() {
        long currentTime = System.currentTimeMillis();
        typingUsers.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue()) > 3000 // Remove after 3 seconds of inactivity
        );
        if (!typingUsers.isEmpty()) {
            broadcastTypingIndicator();
        }
    }
    
    public List<String> getUserNames() {
        return clients.stream()
            .map(ClientHandler::getUsername)
            .sorted()
            .toList();
    }
    
    public void shutdown() {
        typingCleanup.shutdown();
    }
}

class PrivateChatSession {
    private final ClientHandler requester;
    private final ClientHandler target;
    private boolean active = false;
    
    public PrivateChatSession(ClientHandler requester, ClientHandler target) {
        this.requester = requester;
        this.target = target;
    }
    
    public ClientHandler getRequester() { return requester; }
    public ClientHandler getTarget() { return target; }
    public boolean isActive() { return active; }
    
    public void activate() { this.active = true; }
    
    public void broadcastPrivateMessage(String message, boolean isSystem) {
        String formattedMessage;
        if (isSystem) {
            formattedMessage = String.format("\u001B[36m[%s] [SERVER] %s\u001B[0m",
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                message
            );
        } else {
            formattedMessage = String.format("\u001B[35m[%s] [Private] %-15s: %s\u001B[0m",
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                requester.getUsername(),
                message
            );
        }
        
        requester.sendMessage(formattedMessage);
        target.sendMessage(formattedMessage);
    }
    
    public void sendPrivateMessage(String message, ClientHandler sender) {
        String formattedMessage = String.format("\u001B[35m[%s] [Private] %-15s: %s\u001B[0m",
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            sender.getUsername(),
            message
        );
        
        ClientHandler receiver = (sender == requester) ? target : requester;
        sender.sendOwnMessage(formattedMessage.replace("\u001B[35m", "\u001B[32m"));
        receiver.sendMessage(formattedMessage);
    }
}

class ClientHandler implements Runnable {
    private final Socket socket;
    private final int clientId;
    private final ChatServer server;
    private BufferedReader reader;
    private PrintWriter writer;
    private String username;
    private ChatRoom currentRoom;
    private PrivateChatSession privateChatSession;
    
    public ClientHandler(Socket socket, int clientId, ChatServer server) {
        this.socket = socket;
        this.clientId = clientId;
        this.server = server;
    }
    
    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            
            // Authentication/username setup
            setupUsername();
            
            // Join default lobby
            server.joinRoom("Lobby", this);
            
            // Main message loop
            String message;
            while ((message = reader.readLine()) != null) {
                handleMessage(message);
            }
        } catch (IOException e) {
            System.out.println("Client #" + clientId + " disconnected: " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    private void setupUsername() throws IOException {
        sendSystemMessage("Welcome to LAN Chat Server!");
        sendSystemMessage("Enter your username: ");
        
        String input;
        while ((input = reader.readLine()) != null) {
            input = input.trim();
            if (input.isEmpty()) {
                sendSystemMessage("Username cannot be empty. Enter your username: ");
                continue;
            }
            if (input.length() > 15) {
                sendSystemMessage("Username too long (max 15 characters). Enter your username: ");
                continue;
            }
            this.username = input;
            sendSystemMessage("Username set to: " + username);
            sendSystemMessage("Type /help for available commands");
            break;
        }
    }
    
    private void handleMessage(String message) {
        if (message.startsWith("/")) {
            handleCommand(message);
        } else {
            handleChatMessage(message);
        }
    }
    
    private void handleCommand(String command) {
        String[] parts = command.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";
        
        try {
            switch (cmd) {
                case "create":
                    if (argument.isEmpty()) {
                        sendSystemMessage("Usage: /create <roomName>");
                    } else if (server.createRoom(argument, this)) {
                        server.joinRoom(argument, this);
                        sendSystemMessage("Room '" + argument + "' created and joined!");
                    } else {
                        sendSystemMessage("Room '" + argument + "' already exists!");
                    }
                    break;
                    
                case "join":
                    if (argument.isEmpty()) {
                        sendSystemMessage("Usage: /join <roomName>");
                    } else if (server.joinRoom(argument, this)) {
                        sendSystemMessage("Joined room: " + argument);
                    } else {
                        sendSystemMessage("Room '" + argument + "' does not exist!");
                    }
                    break;
                    
                case "leave":
                    if (currentRoom != null && !currentRoom.getName().equals("Lobby")) {
                        String roomName = currentRoom.getName();
                        server.leaveRoom(this);
                        sendSystemMessage("Left room: " + roomName);
                    } else {
                        sendSystemMessage("You are already in the Lobby");
                    }
                    break;
                    
                case "rooms":
                    List<String> roomList = server.getRoomNames();
                    sendSystemMessage("Available rooms: " + String.join(", ", roomList));
                    break;
                    
                case "who":
                    if (currentRoom != null) {
                        List<String> users = currentRoom.getUserNames();
                        sendSystemMessage("Users in " + currentRoom.getName() + ": " + String.join(", ", users));
                    }
                    break;
                    
                case "private":
                    if (argument.isEmpty()) {
                        sendSystemMessage("Usage: /private <username>");
                    } else if (privateChatSession != null) {
                        sendSystemMessage("You are already in a private chat. Use /exit first.");
                    } else if (server.requestPrivateChat(this, argument)) {
                        sendSystemMessage("Private chat request sent to " + argument);
                    } else {
                        sendSystemMessage("User '" + argument + "' not found or invalid!");
                    }
                    break;
                    
                case "exit":
                    if (privateChatSession != null) {
                        server.endPrivateChat(privateChatSession);
                        sendSystemMessage("Private chat ended. Returned to Lobby.");
                    } else {
                        sendSystemMessage("You are not in a private chat.");
                    }
                    break;
                    
                case "help":
                    showHelp();
                    break;
                    
                case "typing_on":
                    if (privateChatSession != null) {
                        // Private chat typing indicator would go here
                    } else if (currentRoom != null) {
                        currentRoom.setUserTyping(this);
                    }
                    break;
                    
                case "typing_off":
                    if (privateChatSession != null) {
                        // Private chat typing indicator would go here
                    } else if (currentRoom != null) {
                        currentRoom.setUserStoppedTyping(this);
                    }
                    break;
                    
                default:
                    sendSystemMessage("Unknown command: " + cmd);
                    break;
            }
        } catch (Exception e) {
            sendSystemMessage("Error processing command: " + e.getMessage());
        }
    }
    
    private void handleChatMessage(String message) {
        if (privateChatSession != null && privateChatSession.isActive()) {
            privateChatSession.sendPrivateMessage(message, this);
        } else if (currentRoom != null) {
            currentRoom.broadcastMessage(message, this);
        } else {
            sendSystemMessage("You are not in any room. Join a room to chat.");
        }
    }
    
    private void showHelp() {
        String help = """
            Available Commands:
            /create <room>    - Create new room
            /join <room>      - Join room
            /leave            - Leave current room
            /rooms            - List all rooms
            /who              - List users in current room
            /private <user>   - Start private chat
            /exit             - Exit private chat
            /help             - Show this help
            """;
        sendSystemMessage(help);
    }
    
    public void sendMessage(String message) {
        writer.println(message);
    }
    
    public void sendOwnMessage(String message) {
        writer.println("\u001B[32m" + message + "\u001B[0m");
    }
    
    public void sendSystemMessage(String message) {
        writer.println("\u001B[36m[SERVER] " + message + "\u001B[0m");
    }
    
    public void sendTypingIndicator(String indicator) {
        writer.println("TYPING_INDICATOR:" + indicator);
    }
    
    public void sendPrivateRequest(String requesterUsername) {
        writer.println("\u001B[36m[SERVER] " + requesterUsername + " wants to start a private chat. Type 'yes' to accept or 'no' to decline.\u001B[0m");
    }
    
    // Handle yes/no responses for private chat requests
    public boolean handlePrivateResponse(String response, String requesterUsername) {
        if (response.equalsIgnoreCase("yes")) {
            server.acceptPrivateChat(this, requesterUsername);
            return true;
        } else if (response.equalsIgnoreCase("no")) {
            server.declinePrivateChat(this, requesterUsername);
            return true;
        }
        return false;
    }
    
    private void cleanup() {
        server.removeClient(this);
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.out.println("Error cleaning up client #" + clientId + ": " + e.getMessage());
        }
    }
    
    // Getters
    public int getClientId() { return clientId; }
    public String getUsername() { return username; }
    public ChatRoom getCurrentRoom() { return currentRoom; }
    public PrivateChatSession getPrivateChatSession() { return privateChatSession; }
    
    // Setters
    public void setCurrentRoom(ChatRoom room) { this.currentRoom = room; }
    public void setPrivateChatSession(PrivateChatSession session) { this.privateChatSession = session; }
}