package com.shobhit.Network_lab;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class EventBus {

    // All subscribers (thread-safe list)
    private final List<EventSubscriber> subscribers =
            new CopyOnWriteArrayList<>();

    // Internal queue (replaces Server.messageQueue)
    private final BlockingQueue<ChatEvent> eventQueue =
            new LinkedBlockingQueue<>();

    // Background worker thread
    private final Thread workerThread;

    public EventBus() {
        // Worker: takes events from queue → fans out to all subscribers
        workerThread = new Thread(() -> {
            while (true) {
                try {
                    ChatEvent event = eventQueue.take(); // blocking
                    for (EventSubscriber subscriber : subscribers) {
                        subscriber.onEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        workerThread.setDaemon(true);
        workerThread.setName("EventBus-Worker");
        workerThread.start();
    }

    // Anyone can subscribe
    public void subscribe(EventSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    // Anyone can publish — just puts into queue
    public void publish(ChatEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
