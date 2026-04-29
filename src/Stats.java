public class Stats {
    public long dataBytes;
    public int packetsSent;
    public int packetsReceived;
    public int outOfOrder;
    public int badChecksum;
    public int retransmissions;
    public int dupAcks;

    public void print() {
        String data;
        if (dataBytes >= 1024L * 1024 * 1024) {
            data = String.format("%.2fGb", dataBytes / (1024.0 * 1024 * 1024));
        } else if (dataBytes >= 1024 * 1024) {
            data = String.format("%.2fMb", dataBytes / (1024.0 * 1024));
        } else if (dataBytes >= 1024) {
            data = String.format("%.2fKb", dataBytes / 1024.0);
        } else {
            data = dataBytes + "b";
        }
        System.out.println(data + " " + packetsSent + " " + packetsReceived
            + " " + outOfOrder + " " + badChecksum
            + " " + retransmissions + " " + dupAcks);
    }
}
