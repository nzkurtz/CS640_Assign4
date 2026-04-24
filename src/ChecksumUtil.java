public final class ChecksumUtil {
    private ChecksumUtil() {}

    public static short compute(byte[] bytes) {
        long sum = onesComplementSum(bytes);
        return (short) (~sum & 0xFFFF);
    }

    public static boolean verify(byte[] bytes) {
        return onesComplementSum(bytes) == 0xFFFFL;
    }

    private static long onesComplementSum(byte[] bytes) {
        long sum = 0;
        int i = 0;

        while (i < bytes.length) {
            int high = bytes[i] & 0xFF;
            int low = 0;

            if (i + 1 < bytes.length) {
                low = bytes[i + 1] & 0xFF;
            }

            int word = (high << 8) | low;
            sum += word;

            while ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >>> 16);
            }

            i += 2;
            
        }
        return sum & 0xFFFFL;
    }
}
