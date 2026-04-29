import java.net.*;
import java.util.Arrays;
import java.util.Random;

/**
 * UDP proxy that randomly drops packets in both directions.
 * Usage: java LossyProxy <listenPort> <destHost> <destPort> <dropRate> [seed]
 *
 * Example (10% loss, sender→proxy:5001, proxy→receiver:5002):
 *   java LossyProxy 5001 127.0.0.1 5002 0.10
 */
public class LossyProxy {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println(
                "Usage: LossyProxy <listenPort> <destHost> <destPort> <dropRate> [seed]");
            System.exit(1);
        }

        int listenPort     = Integer.parseInt(args[0]);
        String destHost    = args[1];
        int destPort       = Integer.parseInt(args[2]);
        double dropRate    = Double.parseDouble(args[3]);
        long seed          = args.length >= 5 ? Long.parseLong(args[4]) : System.nanoTime();

        InetAddress destAddr = InetAddress.getByName(destHost);
        Random rng = new Random(seed);

        // clientSocket: faces the sender
        DatagramSocket clientSocket = new DatagramSocket(listenPort);
        // serverSocket: faces the receiver (OS picks an ephemeral port)
        DatagramSocket serverSocket = new DatagramSocket();

        System.err.printf("LossyProxy: listen=%d  dest=%s:%d  dropRate=%.0f%%  seed=%d%n",
            listenPort, destHost, destPort, dropRate * 100, seed);

        // Remember where the sender is so we can forward replies back
        final InetSocketAddress[] senderRef = {null};

        // Thread: receiver → sender (reverse direction)
        Thread revThread = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (true) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    serverSocket.receive(pkt);

                    InetSocketAddress sender = senderRef[0];
                    if (sender == null) continue;

                    if (rng.nextDouble() < dropRate) {
                        System.err.println("DROP rcv→snd");
                        continue;
                    }

                    byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());
                    clientSocket.send(new DatagramPacket(
                        data, data.length, sender.getAddress(), sender.getPort()));

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "proxy-rev");
        revThread.setDaemon(true);
        revThread.start();

        // Main thread: sender → receiver (forward direction)
        byte[] buf = new byte[65535];
        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            clientSocket.receive(pkt);
            senderRef[0] = new InetSocketAddress(pkt.getAddress(), pkt.getPort());

            if (rng.nextDouble() < dropRate) {
                System.err.println("DROP snd→rcv");
                continue;
            }

            byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());
            serverSocket.send(new DatagramPacket(data, data.length, destAddr, destPort));
        }
    }
}
