public final class TimeoutEstimator {
    private static final double A = 0.875;
    private static final double B = 0.75;
    private static final long DEFAULT_TIMEOUT_NS = 5_000_000_000L;
    private static final long MIN_TIMEOUT_NS = 10_000_000L; // 10 ms
    private static final long MAX_TIMEOUT_NS = 30_000_000_000L; // 30 s

    private long timeoutNs = DEFAULT_TIMEOUT_NS;
    private double ertt = -1.0;
    private double edev = 0.0;

    public long currentTimeoutNanos() {
        return timeoutNs;
    }

    public void updateFromAck(int ackNumber, long echoedTimestamp, long nowNano) {
        long sample = Math.max(1L, nowNano - echoedTimestamp);
        if (ertt < 0.0 || ackNumber == 0) {
            ertt = sample;
            edev = 0.0;
            timeoutNs = clamp((long) (2.0 * ertt));
            return;
        }

        double srtt = sample;
        double sdev = Math.abs(srtt - ertt);
        ertt = A * ertt + (1.0 - A) * srtt;
        edev = B * edev + (1.0 - B) * sdev;
        timeoutNs = clamp((long) (ertt + 4.0 * edev));
    }

    private long clamp(long value) {
        if (value < MIN_TIMEOUT_NS) {
            return MIN_TIMEOUT_NS;
        }
        if (value > MAX_TIMEOUT_NS) {
            return MAX_TIMEOUT_NS;
        }
        return value;
    }
}
