public class Logger {
    private final long startNs = System.nanoTime();

    public synchronized void log(String dir, Segment seg) {
        double t = (System.nanoTime() - startNs) / 1e9;
        String s = seg.syn ? "S" : "-";
        String a = seg.ack ? "A" : "-";
        String f = seg.fin ? "F" : "-";
        String d = (seg.dataLen > 0) ? "D" : "-";
        System.out.printf("%s %.3f %s %s %s %s %d %d %d%n",
            dir, t, s, a, f, d, seg.seqNum, seg.dataLen, seg.ackNum);
    }
}
