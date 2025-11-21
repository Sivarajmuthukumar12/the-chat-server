import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ChatClient {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Scanner scanner;
    private AtomicBoolean connected = new AtomicBoolean(false);
    private AtomicBoolean inPrivateChat = new AtomicBoolean(false);
    private String currentTypingIndicator = "";
    private AtomicReference<String> lastInputRef = new AtomicReference<>("");
    
    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java ChatClient <serverIP> <port>");
            System.out.println("Example: java ChatClient 192.168.1.100 8080");
            return;
        }
        
        String serverIP = args[0];
        int port;
        
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + args[1]);
            return;
        }
        
        ChatClient client = new ChatClient();
        client.start(serverIP, port);
    }
    
    public void start(String serverIP, int port) {
        scanner = new Scanner(System.in);
        
        // Connection retry loop
        while (!connected.get()) {
            try {
                System.out.println("Connecting to " + serverIP + ":" + port + "...");
                socket = new Socket(serverIP, port);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);
                connected.set(true);
                
                System.out.println("Connected to server!");
                startMessageReader();
                startUserInputHandler();
                
            } catch (IOException e) {
                System.out.println("Connection failed: " + e.getMessage());
                System.out.println("Retrying in 5 seconds...");
                
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    
    private void startMessageReader() {
        Thread readerThread = new Thread(() -> {
            try {
                String message;
                while (connected.get() && (message = reader.readLine()) != null) {
                    if (message.startsWith("TYPING_INDICATOR:")) {
                        currentTypingIndicator = message.substring(17);
                        refreshDisplay();
                    } else if (message.contains("wants to start a private chat")) {
                        // This is a private chat request - handle specially
                        System.out.println("\n" + message);
                        System.out.print("Enter your response (yes/no): ");
                    } else {
                        clearTypingLine();
                        System.out.println(message);
                        if (!currentTypingIndicator.isEmpty()) {
                            System.out.print("\u001B[33m" + currentTypingIndicator + "\u001B[0m");
                        }
                    }
                }
            } catch (IOException e) {
                if (connected.get()) {
                    System.out.println("Disconnected from server: " + e.getMessage());
                    connected.set(false);
                    attemptReconnect();
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    private void startUserInputHandler() {
        boolean wasTyping = false;
        
        while (connected.get() && scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            
            // Handle private chat responses
            if (input.equalsIgnoreCase("yes") || input.equalsIgnoreCase("no")) {
                writer.println(input);
                continue;
            }
            
            if (input.isEmpty()) {
                continue;
            }
            
            // Send typing off for previous input if we were typing
            if (wasTyping && !input.startsWith("/")) {
                writer.println("/typing_off");
                wasTyping = false;
            }
            
            if (input.startsWith("/")) {
                writer.println(input);
                
                // Special handling for exit command
                if (input.equals("/exit")) {
                    inPrivateChat.set(false);
                }
            } else {
                writer.println(input);
            }
            
            // Start typing indicator for new non-command input
            if (!input.startsWith("/") && !input.isEmpty()) {
                writer.println("/typing_on");
                wasTyping = true;
            }
            
            lastInputRef.set(input);
            
            // Small delay before sending typing_off to allow for continuous typing
            if (wasTyping) {
                final String currentInput = input; // Create final copy for lambda
                new Thread(() -> {
                    try {
                        Thread.sleep(2000); // Wait 2 seconds after last input
                        if (lastInputRef.get().equals(currentInput)) { // If no new input
                            writer.println("/typing_off");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        }
    }
    
    private void clearTypingLine() {
        if (!currentTypingIndicator.isEmpty()) {
            // Clear the typing indicator line
            System.out.print("\r\033[K");
            currentTypingIndicator = "";
        }
    }
    
    private void refreshDisplay() {
        // This would be more sophisticated in a real GUI
        // For terminal, we simply re-print the typing indicator
        if (!currentTypingIndicator.isEmpty()) {
            System.out.print("\r\033[K"); // Clear line
            System.out.print("\u001B[33m" + currentTypingIndicator + "\u001B[0m");
            System.out.flush();
        }
    }
    
    private void attemptReconnect() {
        System.out.println("Attempting to reconnect...");
        
        while (!connected.get()) {
            try {
                Thread.sleep(5000);
                
                // Get connection details from user or use previous
                System.out.println("Please enter server IP [" + socket.getInetAddress().getHostAddress() + "]: ");
                String ip = scanner.nextLine().trim();
                if (ip.isEmpty()) ip = socket.getInetAddress().getHostAddress();
                
                System.out.println("Please enter port [8080]: ");
                String portStr = scanner.nextLine().trim();
                int port = portStr.isEmpty() ? 8080 : Integer.parseInt(portStr);
                
                socket = new Socket(ip, port);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);
                connected.set(true);
                
                System.out.println("Reconnected successfully!");
                startMessageReader();
                
            } catch (Exception e) {
                System.out.println("Reconnection failed: " + e.getMessage());
                System.out.println("Retrying in 5 seconds...");
            }
        }
    }
    
    private void cleanup() {
        connected.set(false);
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
            if (scanner != null) scanner.close();
        } catch (IOException e) {
            System.out.println("Error during cleanup: " + e.getMessage());
        }
    }
}