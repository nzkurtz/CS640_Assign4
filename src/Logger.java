import java.util.Locale;

public final class Logger {
    private final long startNano;

    public Logger() {
        this.startNano = System.nanoTime();
    }

    public void logSend(Segment segment) {
        log("snd", segment);
    }

    public void logReceive(Segment segment) {
        log("rcv", segment);
    }

    private void log(String direction, Segment segment) {
        double seconds = (System.nanoTime() - startNano) / 1_000_000_000.0;
        String syn = segment.syn ? "S" : "-";
        String ack = segment.ack ? "A" : "-";
        String fin = segment.fin ? "F" : "-";
        String data = segment.hasData() ? "D" : "-";
        System.out.printf(Locale.US, "%s %.3f %s %s %s %s %d %d %d%n",
                direction,
                seconds,
                syn,
                ack,
                fin,
                data,
                segment.seqNum,
                segment.length,
                segment.ackNum);
        System.out.flush();
    }
}
