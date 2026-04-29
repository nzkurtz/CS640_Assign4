public class TCPend {
    public static void main(String[] args) throws Exception {
        int port = -1;
        String remoteIP = null;
        int remotePort = -1;
        String filename = null;
        int mtu = -1;
        int sws = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p": port = Integer.parseInt(args[++i]); break;
                case "-s": remoteIP = args[++i]; break;
                case "-a": remotePort = Integer.parseInt(args[++i]); break;
                case "-f": filename = args[++i]; break;
                case "-m": mtu = Integer.parseInt(args[++i]); break;
                case "-c": sws = Integer.parseInt(args[++i]); break;
            }
        }

        if (remoteIP != null) {
            // Sender mode
            new Sender(port, remoteIP, remotePort, filename, mtu, sws).run();
        } else {
            // Receiver mode
            new Receiver(port, filename, mtu, sws).run();
        }
    }
}
