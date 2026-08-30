package com.shobhit.Network_lab;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ServerMetrics {

    private final AtomicInteger connectedUsers = new AtomicInteger(0);
    private final AtomicLong totalMessages     = new AtomicLong(0);
    private final AtomicLong totalJoins        = new AtomicLong(0);
    private final AtomicLong totalLeaves       = new AtomicLong(0);

    public void userJoined()  { connectedUsers.incrementAndGet(); totalJoins.incrementAndGet(); }
    public void userLeft()    { connectedUsers.decrementAndGet(); totalLeaves.incrementAndGet(); }
    public void messageSent() { totalMessages.incrementAndGet(); }

    public int  getConnectedUsers() { return connectedUsers.get(); }
    public long getTotalMessages()  { return totalMessages.get(); }
    public long getTotalJoins()     { return totalJoins.get(); }
    public long getTotalLeaves()    { return totalLeaves.get(); }

    public void printStats() {
        System.out.println("==== Server Stats ====");
        System.out.println("Online now   : " + getConnectedUsers());
        System.out.println("Total joins  : " + getTotalJoins());
        System.out.println("Total leaves : " + getTotalLeaves());
        System.out.println("Total msgs   : " + getTotalMessages());
        System.out.println("======================");
    }
}