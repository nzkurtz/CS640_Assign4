import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.TreeMap;

public class Receiver {
    private static final int MAX_RETRANS = 16;

    private final int localPort;
    private final String outputFile;
    private final int mtu;
    private final int sws;

    private final Stats stats = new Stats();
    private final Logger logger = new Logger();
    private final TimeoutEstimator timeoutEst = new TimeoutEstimator();

    public Receiver(int localPort, String outputFile, int mtu, int sws) {
        this.localPort = localPort;
        this.outputFile = outputFile;
        this.mtu = mtu;
        this.sws = sws;
    }

    public void run() throws Exception {
        DatagramSocket socket = new DatagramSocket(localPort);
        // wait forever initially
        socket.setSoTimeout(0); 

        try {
            // Wait for SYN
            InetSocketAddress peer = null;
            Segment syn = null;
            while (syn == null) {
                RecvResult r = recvFrom(socket);
                // bad checksum
                if (r == null) continue; 
                if (r.seg.syn && !r.seg.ack && r.seg.seqNum == 0) {
                    syn = r.seg;
                    peer = r.peer;
                }
            }

            int expectedSeq = 1; 
            int mySeq = 0;       

            // Send SYN-ACK
            Segment synAck = new Segment();
            synAck.seqNum = mySeq;
            synAck.ackNum = expectedSeq;
            synAck.timestamp = syn.timestamp; 
            synAck.dataLen = 0;
            synAck.syn = true;
            synAck.ack = true;
            sendTo(socket, peer, synAck);
            mySeq = 1; 

            // Receive data
            TreeMap<Integer, Segment> buffer = new TreeMap<>();
            ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
            int finSeq = -1;

            mainLoop:
            while (true) {
                RecvResult r = recvFrom(socket);
                // bad checksum
                if (r == null) continue; 

                Segment seg = r.seg;

                // Ignore pure ACKs from sender
                if (!seg.syn && !seg.fin && seg.dataLen == 0) continue;

                // Handle data
                if (seg.dataLen > 0) {
                    if (seg.seqNum == expectedSeq) {
                        fileOut.write(seg.data, 0, seg.data.length);
                        stats.dataBytes += seg.dataLen;
                        expectedSeq += seg.dataLen;

                        // Flush any buffered segments that are now in order
                        while (buffer.containsKey(expectedSeq)) {
                            Segment buffered = buffer.remove(expectedSeq);
                            fileOut.write(buffered.data, 0, buffered.data.length);
                            stats.dataBytes += buffered.dataLen;
                            expectedSeq += buffered.dataLen;
                        }
                    } else if (seg.seqNum > expectedSeq) {
                        if (!buffer.containsKey(seg.seqNum)) {
                            buffer.put(seg.seqNum, seg);
                            stats.outOfOrder++;
                        }
                    } else {
                    }
                }

                // FIN
                if (seg.fin) {
                    finSeq = seg.seqNum;
                }

                // Check if FIN is now in order
                if (finSeq >= 0 && expectedSeq == finSeq) {
                    expectedSeq++;

                    // Send FIN + ACK
                    Segment finAck = new Segment();
                    finAck.seqNum = mySeq;
                    finAck.ackNum = expectedSeq;
                    finAck.timestamp = seg.timestamp;
                    finAck.dataLen = 0;
                    finAck.fin = true;
                    finAck.ack = true;
                    sendTo(socket, peer, finAck);

                    // Wait for final sender ACK 
                    for (int attempt = 0; attempt < MAX_RETRANS; attempt++) {
                        socket.setSoTimeout(timeoutEst.getMillis());
                        try {
                            RecvResult resp = recvFrom(socket);
                            if (resp != null && resp.seg.ack && !resp.seg.fin
                                    && resp.seg.ackNum == mySeq + 1) {
                                break;
                            }
            
                            if (resp != null && resp.seg.fin) {
                                finAck.timestamp = resp.seg.timestamp;
                                sendTo(socket, peer, finAck);
                            }
                        } catch (SocketTimeoutException e) {
                            // Retransmit FIN+ACK
                            finAck.timestamp = System.nanoTime();
                            sendTo(socket, peer, finAck);
                        }
                    }
                    break mainLoop;
                }

                // Send cumulative ACK for every valid packet
                Segment ack = new Segment();
                ack.seqNum = mySeq;
                ack.ackNum = expectedSeq;
                ack.timestamp = seg.timestamp;
                ack.dataLen = 0;
                ack.ack = true;
                sendTo(socket, peer, ack);
            }

            // Write received data to file
            Files.write(java.nio.file.Path.of(outputFile), fileOut.toByteArray());

        } finally {
            socket.close();
        }
        stats.print();
    }


    private void sendTo(DatagramSocket socket, InetSocketAddress peer, Segment seg)
            throws IOException {
        byte[] bytes = seg.toBytes();
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length,
                peer.getAddress(), peer.getPort());
        socket.send(pkt);
        logger.log("snd", seg);
        stats.packetsSent++;
    }

    /** Returns null on bad checksum. Never returns null on timeout */
    private RecvResult recvFrom(DatagramSocket socket) throws IOException {
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
        InetSocketAddress peer = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
        return new RecvResult(seg, peer);
    }

    private static class RecvResult {
        final Segment seg;
        final InetSocketAddress peer;
        RecvResult(Segment seg, InetSocketAddress peer) {
            this.seg = seg;
            this.peer = peer;
        }
    }
}
