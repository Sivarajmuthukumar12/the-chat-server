1. Start the Server
Open Command Prompt/Terminal and run:

bash
java -jar ChatServer.jar
Expected Output:

text
Chat Server started on port 8080 - Binding to all network interfaces
Server ready for connections...
2. Find Your Server IP Address
On Windows:

cmd
ipconfig
Look for "IPv4 Address" under your network adapter (usually 192.168.x.x or 10.x.x.x)

On Linux/Mac:

bash
ifconfig
or

bash
ip addr show
3. Run Clients on Same Network
On other computers, run:

bash
java -jar ChatClient.jar <SERVER_IP> 8080
Example:

bash
java -jar ChatClient.jar 192.168.1.100 8080
java -jar ChatClient.jar 192.168.1.100 8080

📋 Simple Distribution Plan
Option A: Fixed Server
Choose one computer as permanent server

Note its IP address

Distribute clients with that IP

Option B: Flexible Server
Anyone can run server on their computer

Share their IP with others

Clients connect to that IP
