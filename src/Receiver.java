import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

public final class Receiver {
    private static final int MAX_RETRANSMISSIONS = 16;
    private static final int RECEIVE_BUFFER_SIZE = 65535;

    private final int localPort;
    private final String outputFileName;
    private final int mtu;
    private final int sws;
    private final Logger logger;
    private final Stats stats;

    public Receiver(int localPort, String outputFileName, int mtu, int sws, Logger logger, Stats stats) {
        this.localPort = localPort;
        this.outputFileName = outputFileName;
        this.mtu = mtu;
        this.sws = sws;
        this.logger = logger;
        this.stats = stats;
    }

    public void run() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(localPort)) {
            socket.setSoTimeout(0);

            Segment syn;
            InetSocketAddress peer;
            while (true) {
                ReceivedPacket received = receiveSegment(socket);
                syn = received.segment;
                peer = received.peer;
                if (syn.syn && syn.seqNum == 0) {
                    break;
                }
            }

            int expectedSeq = syn.seqNum + syn.consumedSequenceSpace();
            int localSeq = 0;
            Segment synAck = Segment.create(localSeq, expectedSeq, syn.timestamp, true, false, true, null);
            sendSegment(socket, peer, synAck);
            localSeq += synAck.consumedSequenceSpace();

            boolean established = false;
            ReceivedPacket pendingData = null;
            while (!established) {
                ReceivedPacket received = receiveSegment(socket);
                Segment segment = received.segment;
                if (segment.syn && segment.seqNum == 0) {
                    sendSegment(socket, received.peer, Segment.create(0, expectedSeq, segment.timestamp, true, false, true, null));
                    continue;
                }
                if (segment.ack && !segment.syn && !segment.fin && !segment.hasData() && segment.ackNum == localSeq) {
                    established = true;
                    break;
                }
                if (segment.hasData()) {
                    pendingData = received;
                    established = true;
                    break;
                }
            }

            TreeMap<Integer, Segment> buffer = new TreeMap<>();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long lastAckTimestamp = syn.timestamp;
            boolean closing = false;
            int closeAttempts = 0;
            Segment finAck = null;
            long closeDeadline = 0L;

            while (true) {
                ReceivedPacket received;
                if (!closing && pendingData != null) {
                    received = pendingData;
                    pendingData = null;
                } else {
                    received = closing
                            ? receiveSegmentWithDeadline(socket, closeDeadline)
                            : receiveSegment(socket);
                }
                if (received == null) {
                    if (!closing || finAck == null) {
                        continue;
                    }
                    if (closeAttempts >= MAX_RETRANSMISSIONS) {
                        throw new IOException("Did not receive final ACK for receiver FIN");
                    }
                    stats.incrementRetransmissions();
                    sendSegment(socket, peer, finAck);
                    closeAttempts += 1;
                    closeDeadline = System.nanoTime() + 5_000_000_000L;
                    continue;
                }
                Segment segment = received.segment;
                peer = received.peer;

                if (segment.syn && segment.seqNum == 0) {
                    sendSegment(socket, peer, Segment.create(0, expectedSeq, syn.timestamp, true, false, true, null));
                    continue;
                }

                if (segment.fin) {
                    while (true) {
                        Segment buffered = buffer.remove(expectedSeq);
                        if (buffered == null) break;
                        out.write(buffered.data);
                        stats.addDataBytes(buffered.length);
                        expectedSeq += buffered.length;
                    }
                    if (segment.seqNum == expectedSeq) {
                        expectedSeq += 1;
                    }
                    finAck = Segment.create(localSeq, expectedSeq, segment.timestamp, false, true, true, null);
                    sendSegment(socket, peer, finAck);
                    closing = true;
                    closeDeadline = System.nanoTime() + 5_000_000_000L;
                    continue;
                }

                if (closing) {
                    if (segment.ack && segment.ackNum == localSeq + 1) {
                        break;
                    }
                    if (segment.fin) {
                        if (closeAttempts >= MAX_RETRANSMISSIONS) {
                            throw new IOException("Did not receive final ACK for receiver FIN");
                        }
                        stats.incrementRetransmissions();
                        sendSegment(socket, peer, finAck);
                        closeAttempts += 1;
                        closeDeadline = System.nanoTime() + 5_000_000_000L;
                    }
                    continue;
                }

                if (!segment.hasData()) {
                    continue;
                }

                if (segment.length > mtu) {
                    continue;
                }

                if (segment.seqNum < expectedSeq) {
                    lastAckTimestamp = segment.timestamp;
                    stats.incrementOutOfSequence();
                    Segment dupAck = Segment.create(localSeq, expectedSeq, lastAckTimestamp, false, false, true, null);
                    sendSegment(socket, peer, dupAck);
                    continue;
                }

                if (segment.seqNum == expectedSeq) {
                    out.write(segment.data);
                    stats.addDataBytes(segment.length);
                    expectedSeq += segment.length;
                    lastAckTimestamp = segment.timestamp;

                    while (true) {
                        Segment buffered = buffer.remove(expectedSeq);
                        if (buffered == null) {
                            break;
                        }
                        out.write(buffered.data);
                        stats.addDataBytes(buffered.length);
                        expectedSeq += buffered.length;
                    }
                    lastAckTimestamp = segment.timestamp;

                    Segment ack = Segment.create(localSeq, expectedSeq, segment.timestamp, false, false, true, null);
                    sendSegment(socket, peer, ack);
                } else {
                    if (!buffer.containsKey(segment.seqNum) && buffer.size() < sws) {
                        buffer.put(segment.seqNum, segment);
                    }
                    lastAckTimestamp = segment.timestamp;
                    stats.incrementOutOfSequence();
                    Segment dupAck = Segment.create(localSeq, expectedSeq, lastAckTimestamp, false, false, true, null);
                    sendSegment(socket, peer, dupAck);
                }
            }

            Files.write(Path.of(outputFileName), out.toByteArray());
        }

        System.out.println(stats.summaryLine());
    }


    private ReceivedPacket receiveSegmentWithDeadline(DatagramSocket socket, long deadlineNanos) throws IOException {
        while (true) {
            long remainingNs = deadlineNanos - System.nanoTime();
            if (remainingNs <= 0) {
                return null;
            }
            int timeoutMs = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, (remainingNs + 999_999L) / 1_000_000L));
            socket.setSoTimeout(timeoutMs);
            DatagramPacket packet = new DatagramPacket(new byte[RECEIVE_BUFFER_SIZE], RECEIVE_BUFFER_SIZE);
            try {
                socket.receive(packet);
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            if (!Segment.verifyChecksum(packet.getData(), packet.getLength())) {
                stats.incrementChecksumDiscarded();
                continue;
            }
            Segment segment;
            try {
                segment = Segment.fromDatagram(packet.getData(), packet.getLength());
            } catch (IllegalArgumentException e) {
                continue;
            }
            logger.logReceive(segment);
            stats.incrementPackets();
            InetAddress address = packet.getAddress();
            int port = packet.getPort();
            return new ReceivedPacket(segment, new InetSocketAddress(address, port));
        }
    }

    private void sendSegment(DatagramSocket socket, InetSocketAddress peer, Segment segment) throws IOException {
        byte[] bytes = segment.toBytes();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, peer);
        socket.send(packet);
        logger.logSend(segment);
        stats.incrementPackets();
    }

    private ReceivedPacket receiveSegment(DatagramSocket socket) throws IOException {
        while (true) {
            DatagramPacket packet = new DatagramPacket(new byte[RECEIVE_BUFFER_SIZE], RECEIVE_BUFFER_SIZE);
            socket.receive(packet);
            if (!Segment.verifyChecksum(packet.getData(), packet.getLength())) {
                stats.incrementChecksumDiscarded();
                continue;
            }
            Segment segment;
            try {
                segment = Segment.fromDatagram(packet.getData(), packet.getLength());
            } catch (IllegalArgumentException e) {
                continue;
            }
            logger.logReceive(segment);
            stats.incrementPackets();
            InetAddress address = packet.getAddress();
            int port = packet.getPort();
            return new ReceivedPacket(segment, new InetSocketAddress(address, port));
        }
    }

    private static final class ReceivedPacket {
        private final Segment segment;
        private final InetSocketAddress peer;

        private ReceivedPacket(Segment segment, InetSocketAddress peer) {
            this.segment = segment;
            this.peer = peer;
        }
    }
}
