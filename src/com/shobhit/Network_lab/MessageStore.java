package com.shobhit.Network_lab;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MessageStore {

	private final Queue<Message> messages = new ConcurrentLinkedQueue<>();
	
	public void save(Message message) {
		
		messages.add(message);
	}
	
	public Queue<Message> getAll(){
		
		return messages;
	}
	
	
}
