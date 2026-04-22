public final class ConnectionState {
    public int localSeq;
    public int peerExpectedSeq;
    public int sendBase;
    public int lastAckNumber = -1;
    public int duplicateAckStreak = 0;

    public ConnectionState(int localSeq, int peerExpectedSeq) {
        this.localSeq = localSeq;
        this.peerExpectedSeq = peerExpectedSeq;
        this.sendBase = localSeq;
    }
}
