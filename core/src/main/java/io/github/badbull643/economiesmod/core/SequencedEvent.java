package io.github.badbull643.economiesmod.core;

public class SequencedEvent {
    public long seq;
    public String prevHash;
    public String hash;
    public String eventType;   // simple class name, so we know what to deserialise into
    public Event event;
    public String signature;
}
