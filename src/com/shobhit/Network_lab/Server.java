package com.shobhit.Network_lab;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Server {

    public static void main(String[] args) {

        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String snsTopicArn = System.getenv("SNS_TOPIC_ARN"); // may be null — that's fine now
        String instanceId = UUID.randomUUID().toString().substring(0, 8);
        System.out.println("[Server] Instance ID: " + instanceId);

        RedisClientRegistry registry = new RedisClientRegistry(redisHost, redisPort);
        ChatRoom chatRoom = new ChatRoom(registry, snsTopicArn, instanceId);

        MessageStore messageStore = new MessageStore();
        MessageDispatcher dispatcher = new MessageDispatcher(messageStore, chatRoom);
        ServerMetrics metrics = new ServerMetrics();

        PrometheusMetricsServer prometheusServer = null;
        try {
            prometheusServer = new PrometheusMetricsServer(9090);
        } catch (IOException e) {
            System.err.println("[Prometheus] Failed to start: " + e.getMessage());
        }

        EventBus eventBus = new EventBus();
        eventBus.subscribe(new MessageSubscriber(dispatcher));
        eventBus.subscribe(new ConsoleLoggerSubscriber());
        eventBus.subscribe(new MetricsSubscriber(metrics, prometheusServer));
        eventBus.subscribe(new FileStorageSubscriber());

        final PrometheusMetricsServer finalPrometheusServer = prometheusServer;
        ExecutorService clientPool = Executors.newCachedThreadPool();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Server] Shutting down...");
            metrics.printStats();
            if (finalPrometheusServer != null) finalPrometheusServer.stop();
            registry.close();
            clientPool.shutdown();
            try {
                if (!clientPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    clientPool.shutdownNow();
                } else {
                    System.out.println("[Server] Clean shutdown.");
                }
            } catch (InterruptedException e) {
                clientPool.shutdownNow();
            }
        }));

        try (ServerSocket serverSocket = new ServerSocket(5000)) {

            System.out.println("Server started on port 5000");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client: "
                        + clientSocket.getInetAddress()
                        + ":" + clientSocket.getPort());
                clientPool.submit(new ClientHandler(clientSocket, chatRoom, eventBus));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}