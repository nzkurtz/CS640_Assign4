public class ChecksumUtil {

    public static short compute(byte[] bytes, int len) {
        int sum = 0;
        int i = 0;
        while (i + 1 < len) {
            sum += ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
            i += 2;
        }
        if (i < len) {
            sum += (bytes[i] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (short) ~sum;
    }

    public static boolean verify(byte[] bytes, int len) {
        int sum = 0;
        int i = 0;
        while (i + 1 < len) {
            sum += ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
            i += 2;
        }
        if (i < len) {
            sum += (bytes[i] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (sum & 0xFFFF) == 0xFFFF;
    }
}
