public class TimeoutEstimator {
    private static final double a = 0.875;
    private static final double b = 0.75;

    private double ertt = 0;
    private double edev = 0;

    private long timeoutNs = 5_000_000_000L;

    // C = current time, T = echoed timestamp, S = sequence number
    public void update(int S, long T, long C) {
        if (S == 0) {
            ertt = (C - T);
            edev = 0;
            timeoutNs = (long) (2 * ertt);
        } else {
            double srtt = (C - T);
            double sdev = Math.abs(srtt - ertt);
            ertt = a * ertt + (1 - a) * srtt;
            edev = b * edev + (1 - b) * sdev;
            timeoutNs = (long) (ertt + 4 * edev);
        }
    }

    public long getNanos() {
        return Math.max(timeoutNs, 15_000_000L);
    }

    public int getMillis() {
        return (int) Math.max(1, getNanos() / 1_000_000);
    }
}
