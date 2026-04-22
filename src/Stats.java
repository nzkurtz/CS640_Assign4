public final class Stats {
    private long dataBytes = 0;
    private long packets = 0;
    private long outOfSequence = 0;
    private long checksumDiscarded = 0;
    private long retransmissions = 0;
    private long duplicateAcks = 0;

    public void addDataBytes(long count) {
        dataBytes += count;
    }

    public void incrementPackets() {
        packets += 1;
    }

    public void incrementOutOfSequence() {
        outOfSequence += 1;
    }

    public void incrementChecksumDiscarded() {
        checksumDiscarded += 1;
    }

    public void incrementRetransmissions() {
        retransmissions += 1;
    }

    public void incrementDuplicateAcks() {
        duplicateAcks += 1;
    }

    public String summaryLine() {
        return String.format("%dB %d %d %d %d %d",
                dataBytes,
                packets,
                outOfSequence,
                checksumDiscarded,
                retransmissions,
                duplicateAcks);
    }
}
