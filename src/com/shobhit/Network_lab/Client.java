package com.shobhit.Network_lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

public class Client {

    private static final Map<String, String> pendingFiles = new ConcurrentHashMap<>();

    public static void main(String[] args) {

        try {
        	String host = (args.length > 0) ? args[0] : "localhost";
        	int port = (args.length > 1) ? Integer.parseInt(args[1]) : 5000;

            // Look for PEER_BINARY_PATH env var first; if not set, try common known
            // locations so the same compiled class works on both this machine and EC2.
            String peerBinaryPath = System.getenv("PEER_BINARY_PATH");
            if (peerBinaryPath == null) {
                String[] knownPaths = {
                    "/home/shobhit/peer-app/target/release/peer",
                    "/home/ubuntu/aperture/peer-app/target/release/peer"
                };
                for (String candidate : knownPaths) {
                    if (new java.io.File(candidate).exists()) {
                        peerBinaryPath = candidate;
                        break;
                    }
                }
            }
            if (peerBinaryPath == null) {
                System.out.println("Could not find peer binary. Set PEER_BINARY_PATH environment variable.");
                return;
            }

            Socket socket = new Socket(host, 5000);
            System.out.println("Connected to server!");

            Scanner sc = new Scanner(System.in);
            System.out.print("Enter username: ");
            String username = sc.nextLine();

            ProcessBuilder pb = new ProcessBuilder(
                    peerBinaryPath,
                    username
            );
            pb.environment().put("RUST_LOG", "iroh=debug");
            pb.redirectErrorStream(true);
            Process peerProcess = pb.start();

            OutputStream peerIn = peerProcess.getOutputStream();
            PrintWriter peerWriter = new PrintWriter(peerIn, true);
            BufferedReader peerOut = new BufferedReader(
                    new InputStreamReader(peerProcess.getInputStream()));

            String peerLine;
            String myEndpointId = null;
            while ((peerLine = peerOut.readLine()) != null) {
                if (peerLine.startsWith("EVENT:ENDPOINT_READY:")) {
                    myEndpointId = peerLine.substring("EVENT:ENDPOINT_READY:".length());
                    break;
                }
            }
            System.out.println("Local peer-app ready, endpoint: " + myEndpointId);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            out.println(username);
            out.println(myEndpointId);

            final BufferedReader finalPeerOut = peerOut;
            Thread peerListener = new Thread(() -> {
                try {
                    String line;
                    while ((line = finalPeerOut.readLine()) != null) {

                        if (line.startsWith("EVENT:PROGRESS:")) {
                            String data = line.substring("EVENT:PROGRESS:".length());
                            String[] p = data.split("\\|");
                            if (p.length >= 5) {
                                ProgressBarRender.render(p[0], p[1], Integer.parseInt(p[2]), p[3], p[4]);
                            }
                        } else {
                            System.out.println("\n[peer-app] " + line);
                        }
                    }
                } catch (IOException e) {
                    // peer-app process ended
                }
            });
            peerListener.setDaemon(true);
            peerListener.start();

            Thread serverListener = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {

                        if (line.startsWith("FILE_REQUEST|")) {
                            String[] parts = line.split("\\|", 4);
                            String from = parts[1];
                            String rest = parts.length > 2 ? parts[2] : "";
                            System.out.println("\n" + from + " wants to send you a file: " + rest);
                            System.out.println("Auto-accepting...");
                            out.println("FILE_ACCEPT|" + from);

                        } else if (line.startsWith("PEER_INFO|")) {
                            String[] parts = line.split("\\|", 3);
                            String senderUsername = parts[1];
                            String senderEndpointId = parts.length > 2 ? parts[2] : "";
                            String filePath = pendingFiles.remove(senderUsername);
                            if (filePath != null) {
                                peerWriter.println("sendto " + senderEndpointId + " " + filePath);
                            }

                        } else if (line.startsWith("FILE_REJECT|")) {
                            System.out.println("\nFile transfer was rejected.");

                        } else {
                            System.out.println("\n" + line);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("\nConnection lost. Exiting...");
                }
                System.exit(0);
            });
            serverListener.setDaemon(true);
            serverListener.start();

            System.out.println("Commands:");
            System.out.println("  <message>                         - send a chat message");
            System.out.println("  /send <username> <file_path>      - send a file to a user");

            while (true) {
                String input = sc.nextLine();

                if (input.startsWith("/send ")) {
                    String[] parts = input.substring(6).split(" ", 2);
                    if (parts.length != 2) {
                        System.out.println("usage: /send <username> <file_path>");
                        continue;
                    }
                    String targetUser = parts[0];
                    String filePath = parts[1];

                    java.io.File f = new java.io.File(filePath);
                    if (!f.exists()) {
                        System.out.println("File not found: " + filePath);
                        continue;
                    }

                    pendingFiles.put(targetUser, filePath);
                    out.println("FILE_REQUEST|" + targetUser + "|" + f.getName() + "|" + f.length());
                    System.out.println("Requested to send '" + f.getName() + "' to " + targetUser + ".");

                } else {
                    out.println(input);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}