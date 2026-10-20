package com.shobhit.Network_lab;

public enum MessageType {

    JOIN,
    LEAVE,
    CHAT,
    REGISTER_ENDPOINT,
    FILE_REQUEST,
    FILE_ACCEPT,
    FILE_REJECT,
    PEER_INFO,
    FILE_READY,
    SESSION_ENDED,
    // B3b: one side tells the other a transfer is over
    // (content: id|state|reason). B3b: state = done. B3c: also failed
    // (e.g. "lost local data", "file changed"): the receiver cancels, the
    // sender stops sharing. cancelled comes in B4.
    TRANSFER_END
}