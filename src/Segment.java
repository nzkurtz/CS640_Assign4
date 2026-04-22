import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Segment {
    public static final int FLAG_ACK = 0x1;
    public static final int FLAG_FIN = 0x2;
    public static final int FLAG_SYN = 0x4;

    public static final int HEADER_SIZE = 24;
    public static final int MAX_LENGTH = 0x1FFFFFFF; // 29 bits

    public final int seqNum;
    public final int ackNum;
    public final long timestamp;
    public final int length;
    public final boolean syn;
    public final boolean fin;
    public final boolean ack;
    public final short checksum;
    public final byte[] data;

    private Segment(
            int seqNum,
            int ackNum,
            long timestamp,
            int length,
            boolean syn,
            boolean fin,
            boolean ack,
            short checksum,
            byte[] data) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.timestamp = timestamp;
        this.length = length;
        this.syn = syn;
        this.fin = fin;
        this.ack = ack;
        this.checksum = checksum;
        this.data = data;
    }

    public static Segment create(int seqNum, int ackNum, long timestamp, boolean syn, boolean fin, boolean ack, byte[] data) {
        byte[] payload = (data == null) ? new byte[0] : Arrays.copyOf(data, data.length);
        if (payload.length > MAX_LENGTH) {
            throw new IllegalArgumentException("Payload too large for 29-bit length field");
        }
        return new Segment(seqNum, ackNum, timestamp, payload.length, syn, fin, ack, (short) 0, payload);
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(seqNum);
        buffer.putInt(ackNum);
        buffer.putLong(timestamp);
        buffer.putInt(encodeLengthAndFlags(length, syn, fin, ack));
        buffer.putShort((short) 0);
        buffer.putShort((short) 0); // checksum placeholder
        buffer.put(data);
        byte[] raw = buffer.array();
        short computedChecksum = ChecksumUtil.compute(raw);
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putShort(22, computedChecksum);
        return raw;
    }

    public static Segment fromDatagram(byte[] packet, int packetLength) {
        if (packetLength < HEADER_SIZE) {
            throw new IllegalArgumentException("Segment too short");
        }
        byte[] raw = Arrays.copyOf(packet, packetLength);
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        int seqNum = buffer.getInt();
        int ackNum = buffer.getInt();
        long timestamp = buffer.getLong();
        int lengthAndFlags = buffer.getInt();
        short zeros = buffer.getShort();
        short checksum = buffer.getShort();

        if (zeros != 0) {
            throw new IllegalArgumentException("Reserved field is non-zero");
        }

        int length = decodeLength(lengthAndFlags);
        boolean syn = (lengthAndFlags & FLAG_SYN) != 0;
        boolean fin = (lengthAndFlags & FLAG_FIN) != 0;
        boolean ack = (lengthAndFlags & FLAG_ACK) != 0;

        if (packetLength != HEADER_SIZE + length) {
            throw new IllegalArgumentException("Invalid segment length");
        }

        byte[] data = new byte[length];
        buffer.get(data);
        return new Segment(seqNum, ackNum, timestamp, length, syn, fin, ack, checksum, data);
    }

    public boolean hasData() {
        return length > 0;
    }

    public int consumedSequenceSpace() {
        int consumed = length;
        if (syn) {
            consumed += 1;
        }
        if (fin) {
            consumed += 1;
        }
        return consumed;
    }

    public static boolean verifyChecksum(byte[] packet, int packetLength) {
        byte[] raw = Arrays.copyOf(packet, packetLength);
        return ChecksumUtil.verify(raw);
    }

    private static int encodeLengthAndFlags(int length, boolean syn, boolean fin, boolean ack) {
        if (length < 0 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid payload length");
        }
        int encoded = (length << 3);
        if (syn) {
            encoded |= FLAG_SYN;
        }
        if (fin) {
            encoded |= FLAG_FIN;
        }
        if (ack) {
            encoded |= FLAG_ACK;
        }
        return encoded;
    }

    private static int decodeLength(int lengthAndFlags) {
        return lengthAndFlags >>> 3;
    }
}
