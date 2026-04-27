import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Sender {
    private static final int MAX_RETRANSMISSIONS = 16;
    private static final int RECEIVE_BUFFER_SIZE = 65535;

    private final int localPort;
    private final String remoteIp;
    private final int remotePort;
    private final String fileName;
    private final int mtu;
    private final int sws;
    private final Logger logger;
    private final Stats stats;
    private final TimeoutEstimator timeoutEstimator;

    public Sender(int localPort, String remoteIp, int remotePort, String fileName, int mtu, int sws,
                  Logger logger, Stats stats) {
        this.localPort = localPort;
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
        this.logger = logger;
        this.stats = stats;
        this.timeoutEstimator = new TimeoutEstimator();
    }

    public void run() throws IOException {
        byte[] fileBytes = Files.readAllBytes(Path.of(fileName));
        InetAddress remoteAddress = InetAddress.getByName(remoteIp);
        InetSocketAddress remoteSocketAddress = new InetSocketAddress(remoteAddress, remotePort);

        try (DatagramSocket socket = new DatagramSocket(localPort)) {
            socket.connect(remoteAddress, remotePort);
            ConnectionState state = performHandshake(socket, remoteSocketAddress);
            List<Segment> segments = buildDataSegments(fileBytes, state.localSeq, state.peerExpectedSeq);
            sendFile(socket, remoteSocketAddress, segments, state);
            int finSeq = state.localSeq;
            Segment fin = Segment.create(finSeq, state.peerExpectedSeq, System.nanoTime(), false, true, false, null);
            performClose(socket, remoteSocketAddress, fin);
        }

        System.out.println(stats.summaryLine());
    }

    private ConnectionState performHandshake(DatagramSocket socket, InetSocketAddress remote) throws IOException {
        Segment syn = Segment.create(0, 0, System.nanoTime(), true, false, false, null);
        int attempts = 0;

        while (attempts <= MAX_RETRANSMISSIONS) {
            if (attempts > 0) {
                stats.incrementRetransmissions();
            }
            sendSegment(socket, remote, syn);
            long deadline = System.nanoTime() + timeoutEstimator.currentTimeoutNanos();

            while (System.nanoTime() < deadline) {
                Segment response = receiveSegmentWithDeadline(socket, deadline);
                if (response == null) {
                    break;
                }
                
                if (!response.syn || !response.ack || response.ackNum != 1) {
                    continue;
                }
                int peerExpectedSeq = response.seqNum + response.consumedSequenceSpace();
                timeoutEstimator.updateFromAck(response.ackNum, response.timestamp, System.nanoTime());
                Segment finalAck = Segment.create(1, peerExpectedSeq, System.nanoTime(), false, false, true, null);
                sendSegment(socket, remote, finalAck);
                
                return new ConnectionState(1, peerExpectedSeq);
            }

            attempts += 1;
        }

        throw new IOException("Handshake failed after maximum retransmissions");
    }

    private List<Segment> buildDataSegments(byte[] fileBytes, int startingSeq, int ackNum) {
        List<Segment> segments = new ArrayList<>();
        int offset = 0;
        int seq = startingSeq;
        while (offset < fileBytes.length) {
            int length = Math.min(mtu, fileBytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(fileBytes, offset, chunk, 0, length);
            segments.add(Segment.create(seq, ackNum, 0L, false, false, true, chunk));
            offset += length;
            seq += length;
        }
        
        return segments;
    }

    private void sendFile(DatagramSocket socket, InetSocketAddress remote, List<Segment> templates, ConnectionState state)
            throws IOException {
        Map<Integer, InFlightSegment> inFlight = new LinkedHashMap<>();
        int nextIndex = 0;

        while (nextIndex < templates.size() || !inFlight.isEmpty()) {
            while (nextIndex < templates.size() && inFlight.size() < sws) {
                Segment template = templates.get(nextIndex);
                Segment live = Segment.create(
                        template.seqNum,
                        state.peerExpectedSeq,
                        System.nanoTime(),
                        false,
                        false,
                        true,
                        template.data);
                sendSegment(socket, remote, live);
                stats.addDataBytes(live.length);
                inFlight.put(live.seqNum, new InFlightSegment(live, System.nanoTime() + timeoutEstimator.currentTimeoutNanos()));
                nextIndex += 1;
            }

            long deadline = earliestDeadline(inFlight);
            Segment ack = receiveSegmentWithDeadline(socket, deadline);
            if (ack != null) {
                handleAck(socket, remote, ack, inFlight, state);
            } else {
                long now = System.nanoTime();
                for (InFlightSegment pending : inFlight.values()) {
                    if (pending.deadlineNanos <= now) {
                        retransmit(socket, remote, pending, state.peerExpectedSeq);
                    }
                }
            }
        }

        if (templates.isEmpty()) {
            state.localSeq = 1;
        } else {
            Segment last = templates.get(templates.size() - 1);
            state.localSeq = last.seqNum + last.length;
        }
    }

    private void handleAck(DatagramSocket socket, InetSocketAddress remote, Segment ack,
                           Map<Integer, InFlightSegment> inFlight, ConnectionState state) throws IOException {
        if (!ack.ack) {
            return;
        }

        int ackNumber = ack.ackNum;
        boolean advanced = false;

        Iterator<Map.Entry<Integer, InFlightSegment>> iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, InFlightSegment> entry = iterator.next();
            int endExclusive = entry.getKey() + entry.getValue().segment.length;
            
            if (ackNumber >= endExclusive) {
                iterator.remove();
                advanced = true;
            }
        }

        if (advanced) {
            timeoutEstimator.updateFromAck(ackNumber, ack.timestamp, System.nanoTime());
            state.sendBase = ackNumber;
            state.lastAckNumber = ackNumber;
            state.duplicateAckStreak = 0;
            
            return;
        }

        if (ackNumber == state.lastAckNumber) {
            state.duplicateAckStreak += 1;
            stats.incrementDuplicateAcks();
            if (state.duplicateAckStreak >= 3) {
                InFlightSegment missing = inFlight.get(ackNumber);
                
                if (missing != null) {
                    retransmit(socket, remote, missing, state.peerExpectedSeq);
                    state.duplicateAckStreak = 0;
                }
            }
        } else {
            state.lastAckNumber = ackNumber;
            state.duplicateAckStreak = 0;
        }
    }

    private void retransmit(DatagramSocket socket, InetSocketAddress remote, InFlightSegment pending, int ackNum)
            throws IOException {
        if (pending.retransmissions >= MAX_RETRANSMISSIONS) {
            throw new IOException("Segment exceeded maximum retransmissions");
        }
        Segment resent = Segment.create(
                pending.segment.seqNum,
                ackNum,
                System.nanoTime(),
                pending.segment.syn,
                pending.segment.fin,
                pending.segment.ack,
                pending.segment.data);
        pending.segment = resent;
        pending.retransmissions += 1;
        pending.deadlineNanos = System.nanoTime() + timeoutEstimator.currentTimeoutNanos();
        stats.incrementRetransmissions();
        sendSegment(socket, remote, resent);
    }

    private long earliestDeadline(Map<Integer, InFlightSegment> inFlight) {
        long fallback = System.nanoTime() + timeoutEstimator.currentTimeoutNanos();
        long earliest = fallback;
        for (InFlightSegment pending : inFlight.values()) {
            earliest = Math.min(earliest, pending.deadlineNanos);
        }
        
        return earliest;
    }

    private void performClose(DatagramSocket socket, InetSocketAddress remote, Segment fin)
            throws IOException {
        int attempts = 0;
        int finSeq = fin.seqNum;

        while (attempts <= MAX_RETRANSMISSIONS) {
            if (attempts > 0) {
                stats.incrementRetransmissions();
            }
            sendSegment(socket, remote, fin);
            long deadline = System.nanoTime() + timeoutEstimator.currentTimeoutNanos();

            while (System.nanoTime() < deadline) {
                Segment response = receiveSegmentWithDeadline(socket, deadline);
                if (response == null) {
                    break;
                }
                
                if (response.fin && response.ack && response.ackNum == finSeq + 1) {
                    int peerFinalSeq = response.seqNum + response.consumedSequenceSpace();
                    Segment finalAck = Segment.create(finSeq + 1, peerFinalSeq, System.nanoTime(), false, false, true, null);
                    sendSegment(socket, remote, finalAck);
                    return;
                }
            }

            attempts += 1;
        }

        throw new IOException("Connection close failed after maximum retransmissions");
    }

    private void sendSegment(DatagramSocket socket, InetSocketAddress remote, Segment segment) throws IOException {
        byte[] bytes = segment.toBytes();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, remote);
        socket.send(packet);
        logger.logSend(segment);
        stats.incrementPackets();
    }

    private Segment receiveSegmentWithDeadline(DatagramSocket socket, long deadlineNanos) throws IOException {
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
            
            return segment;
        }
    }

    private static final class InFlightSegment {
        private Segment segment;
        private int retransmissions;
        private long deadlineNanos;

        private InFlightSegment(Segment segment, long deadlineNanos) {
            this.segment = segment;
            this.retransmissions = 0;
            this.deadlineNanos = deadlineNanos;
        }
    }
}
