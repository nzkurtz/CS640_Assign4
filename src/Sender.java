import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.Arrays;

public class Sender {
    private static final int MAX_RETRANS = 16;

    private final int localPort;
    private final String remoteHost;
    private final int remotePort;
    private final int mtu;
    private final int sws;
    private final String filename;

    private DatagramSocket socket;
    private InetAddress destAddr;
    private byte[] fileData;

    private final Stats stats = new Stats();
    private final Logger logger = new Logger();
    private final TimeoutEstimator timeoutEst = new TimeoutEstimator();

    // Sliding window state
    private Segment[] win;
    private byte[][] winBytes;
    private long[] winTime;
    private int winCount = 0;

    private int base;     // oldest unACKed seq
    private int nextSeq;  // next seq to send
    private int dupAckCount = 0;
    private int lastReceivedAckNum = -1;
    private int consecutiveRetrans = 0;
    private int receiverSeq = 1; // receiver's next seq (after its SYN)

    public Sender(int localPort, String remoteHost, int remotePort,
                  String filename, int mtu, int sws) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.filename = filename;
        this.mtu = mtu;
        this.sws = sws;
    }

    public void run() throws Exception {
        fileData = Files.readAllBytes(java.nio.file.Path.of(filename));
        destAddr = InetAddress.getByName(remoteHost);
        socket = new DatagramSocket(localPort);
        win = new Segment[sws];
        winBytes = new byte[sws][];
        winTime = new long[sws];

        try {
            doSynHandshake();
            doDataTransfer();
            doFinHandshake();
        } finally {
            socket.close();
        }
        stats.print();
    }

    // -------------------------------------------------------------------------
    // Handshake
    // -------------------------------------------------------------------------

    private void doSynHandshake() throws IOException {
        Segment syn = new Segment();
        syn.seqNum = 0;
        syn.ackNum = 0;
        syn.timestamp = System.nanoTime();
        syn.dataLen = 0;
        syn.syn = true;

        for (int attempt = 0; attempt <= MAX_RETRANS; attempt++) {
            if (attempt > 0) {
                stats.retransmissions++;
                syn.timestamp = System.nanoTime();
            }
            sendSeg(syn);

            long deadline = System.nanoTime() + timeoutEst.getNanos();
            Segment resp = recvUntil(deadline);
            if (resp != null && resp.syn && resp.ack) {
                // Update timeout estimator using SYN-ACK echo
                long now = System.nanoTime();
                timeoutEst.update(0, resp.timestamp, now);

                base = resp.ackNum;    // should be 1
                nextSeq = resp.ackNum; // start data at seq 1

                // Send ACK for the SYN-ACK
                Segment ackSeg = new Segment();
                ackSeg.seqNum = base;
                ackSeg.ackNum = resp.seqNum + 1; // receiver's SYN consumed 1 byte
                ackSeg.timestamp = System.nanoTime();
                ackSeg.dataLen = 0;
                ackSeg.ack = true;
                receiverSeq = resp.seqNum + 1;
                sendSeg(ackSeg);
                lastReceivedAckNum = base;
                return;
            }
        }
        throw new RuntimeException("SYN handshake failed after " + MAX_RETRANS + " retransmissions");
    }

    private void doFinHandshake() throws IOException {
        int finSeq = 1 + fileData.length; // seq right after last data byte

        Segment fin = new Segment();
        fin.seqNum = finSeq;
        fin.ackNum = receiverSeq;
        fin.timestamp = System.nanoTime();
        fin.dataLen = 0;
        fin.fin = true;

        Segment finAck = null;
        for (int attempt = 0; attempt <= MAX_RETRANS; attempt++) {
            if (attempt > 0) {
                stats.retransmissions++;
                fin.timestamp = System.nanoTime();
            }
            sendSeg(fin);

            long deadline = System.nanoTime() + timeoutEst.getNanos();
            while (System.nanoTime() < deadline) {
                Segment resp = recvUntil(deadline);
                if (resp == null) break;
                if (resp.fin && resp.ack && resp.ackNum == finSeq + 1) {
                    finAck = resp;
                    break;
                }
                // Ignore other packets (stale ACKs etc.)
            }
            if (finAck != null) break;
        }

        if (finAck == null) {
            throw new RuntimeException("FIN handshake failed");
        }

        // Send final ACK
        Segment lastAck = new Segment();
        lastAck.seqNum = finSeq + 1;
        lastAck.ackNum = finAck.seqNum + 1;
        lastAck.timestamp = System.nanoTime();
        lastAck.dataLen = 0;
        lastAck.ack = true;
        sendSeg(lastAck);
    }

    // -------------------------------------------------------------------------
    // Data transfer
    // -------------------------------------------------------------------------

    private void doDataTransfer() throws IOException {
        int endSeq = 1 + fileData.length; // exclusive end / FIN seq

        while (base < endSeq) {
            // Fill window with new segments
            while (winCount < sws && nextSeq < endSeq) {
                int dataStart = nextSeq - 1;
                int dataLen = Math.min(mtu, fileData.length - dataStart);
                if (dataLen <= 0) break;

                Segment seg = new Segment();
                seg.seqNum = nextSeq;
                seg.ackNum = receiverSeq;
                seg.timestamp = System.nanoTime();
                seg.dataLen = dataLen;
                seg.ack = true;
                seg.data = Arrays.copyOfRange(fileData, dataStart, dataStart + dataLen);

                byte[] bytes = seg.toBytes();
                win[winCount] = seg;
                winBytes[winCount] = bytes;
                winTime[winCount] = System.nanoTime();
                winCount++;

                sendRaw(bytes, seg);
                stats.dataBytes += dataLen;
                nextSeq += dataLen;
            }

            if (winCount == 0) break;

            // Receive with deadline based on oldest in-flight send time
            long deadline = winTime[0] + timeoutEst.getNanos();
            Segment ack = recvUntil(deadline);

            if (ack == null) {
                // Timeout: retransmit all in-flight
                consecutiveRetrans++;
                if (consecutiveRetrans > MAX_RETRANS) {
                    throw new RuntimeException("Max retransmissions exceeded");
                }
                retransmitAll();
            } else {
                if (!ack.ack || ack.syn) continue; // ignore non-ACK and stale SYN-ACKs

                if (ack.ackNum > base) {
                    // Update timeout estimator only on new ACKs
                    long now = System.nanoTime();
                    timeoutEst.update(ack.ackNum, ack.timestamp, now);
                    advanceWindow(ack.ackNum);
                    consecutiveRetrans = 0;
                    dupAckCount = 0;
                    lastReceivedAckNum = ack.ackNum;
                } else if (ack.ackNum == base) {
                    if (ack.ackNum == lastReceivedAckNum) {
                        dupAckCount++;
                    } else {
                        dupAckCount = 1;
                        lastReceivedAckNum = ack.ackNum;
                    }
                    stats.dupAcks++;
                    if (dupAckCount == 3) {
                        // Fast retransmit: only the oldest in-flight
                        resend(0);
                        stats.retransmissions++;
                    }
                } else {
                    // ackNum < base: stale, ignore
                }
            }
        }
    }

    private void advanceWindow(int newBase) {
        int removed = 0;
        while (removed < winCount) {
            int segEnd = win[removed].seqNum + win[removed].dataLen;
            if (segEnd <= newBase) removed++;
            else break;
        }
        for (int i = 0; i < winCount - removed; i++) {
            win[i] = win[i + removed];
            winBytes[i] = winBytes[i + removed];
            winTime[i] = winTime[i + removed];
        }
        winCount -= removed;
        base = newBase;
    }

    private void retransmitAll() throws IOException {
        for (int i = 0; i < winCount; i++) {
            resend(i);
            stats.retransmissions++;
        }
    }

    private void resend(int idx) throws IOException {
        win[idx].timestamp = System.nanoTime();
        winBytes[idx] = win[idx].toBytes();
        winTime[idx] = System.nanoTime();
        sendRaw(winBytes[idx], win[idx]);
    }

    // -------------------------------------------------------------------------
    // Send / Receive helpers
    // -------------------------------------------------------------------------

    private void sendSeg(Segment seg) throws IOException {
        sendRaw(seg.toBytes(), seg);
    }

    private void sendRaw(byte[] bytes, Segment seg) throws IOException {
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, destAddr, remotePort);
        socket.send(pkt);
        logger.log("snd", seg);
        stats.packetsSent++;
    }

    /**
     * Try to receive one well-formed segment. Returns null on bad checksum.
     * Throws SocketTimeoutException if socket times out.
     */
    private Segment recvOnce() throws IOException {
        byte[] buf = new byte[Segment.HEADER_SIZE + mtu + 64];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt); // may throw SocketTimeoutException
        int len = pkt.getLength();
        if (!ChecksumUtil.verify(pkt.getData(), len)) {
            stats.badChecksum++;
            return null;
        }
        Segment seg = Segment.fromBytes(pkt.getData(), len);
        logger.log("rcv", seg);
        stats.packetsReceived++;
        return seg;
    }

    /** Receive a valid segment before deadlineNs. Returns null on timeout. */
    private Segment recvUntil(long deadlineNs) throws IOException {
        while (true) {
            long remaining = deadlineNs - System.nanoTime();
            if (remaining <= 0) return null;
            int ms = (int) Math.max(1, remaining / 1_000_000);
            socket.setSoTimeout(ms);
            try {
                Segment seg = recvOnce();
                if (seg != null) return seg;
                // Bad checksum: retry within deadline
            } catch (SocketTimeoutException e) {
                return null;
            }
        }
    }
}
