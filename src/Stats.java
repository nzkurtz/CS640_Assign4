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
        return String.format("%s %d %d %d %d %d",
                formatDataBytes(dataBytes),
                packets,
                outOfSequence,
                checksumDiscarded,
                retransmissions,
                duplicateAcks);
    }

    private static String formatDataBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + "Mb";
        } else if (bytes >= 1024L) {
            return (bytes / 1024L) + "Kb";
        }
        
        return bytes + "B";
    }
}
