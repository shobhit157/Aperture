package com.shobhit.Network_lab;

// Replaces MessageWorker — now reacts to events via EventBus
public class MessageSubscriber implements EventSubscriber {

    private final MessageDispatcher dispatcher;

    public MessageSubscriber(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onEvent(ChatEvent event) {
        dispatcher.dispatch(event.getMessage());
    }
}