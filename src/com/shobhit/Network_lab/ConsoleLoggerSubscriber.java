package com.shobhit.Network_lab;

public class ConsoleLoggerSubscriber implements EventSubscriber {

    @Override
    public void onEvent(ChatEvent event) {
        System.out.println("[LOG] " + event.toString());
    }
}