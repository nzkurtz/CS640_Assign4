import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class TCPend {
    private TCPend() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> parsed = parseArgs(args);

        int port = requireInt(parsed, "-p");
        int mtu = requireInt(parsed, "-m");
        int sws = requireInt(parsed, "-c");
        String fileName = require(parsed, "-f");

        if (mtu <= 0 || mtu > 1430) {
            throw new IllegalArgumentException("MTU must be between 1 and 1430 for the expected test environment");
        }
        if (sws <= 0) {
            throw new IllegalArgumentException("Sliding window size must be positive");
        }

        Logger logger = new Logger();
        Stats stats = new Stats();

        if (parsed.containsKey("-s")) {
            String remoteIp = require(parsed, "-s");
            int remotePort = requireInt(parsed, "-a");
            Sender sender = new Sender(port, remoteIp, remotePort, fileName, mtu, sws, logger, stats);
            sender.run();
        } else {
            Receiver receiver = new Receiver(port, fileName, mtu, sws, logger, stats);
            receiver.run();
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("-")) {
                throw new IllegalArgumentException("Unexpected argument: " + key);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            parsed.put(key, args[++i]);
        }
        return parsed;
    }

    private static String require(Map<String, String> parsed, String key) {
        String value = parsed.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument " + key);
        }
        return value;
    }

    private static int requireInt(Map<String, String> parsed, String key) {
        return Integer.parseInt(require(parsed, key));
    }
}
