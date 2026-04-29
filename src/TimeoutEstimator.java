public class TimeoutEstimator {
    private static final double A = 0.875;
    private static final double B = 0.75;
    private static final long INITIAL_NS = 5_000_000_000L;

    private boolean first = true;
    private double ertt;
    private double edev;
    private long timeoutNs = INITIAL_NS;

    public void update(long rttNs) {
        if (first) {
            ertt = rttNs;
            edev = 0;
            timeoutNs = (long) (2 * ertt);
            first = false;
        } else {
            double srtt = rttNs;
            double sdev = Math.abs(srtt - ertt);
            ertt = A * ertt + (1 - A) * srtt;
            edev = B * edev + (1 - B) * sdev;
            timeoutNs = (long) (ertt + 4 * edev);
        }
        if (timeoutNs < 1_000_000) timeoutNs = 1_000_000; // minimum 1ms
    }

    public long getNanos() { return timeoutNs; }

    public int getMillis() { return (int) Math.max(1, timeoutNs / 1_000_000); }
}
