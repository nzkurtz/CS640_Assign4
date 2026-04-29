import java.nio.ByteBuffer;

public class Segment {
    public static final int HEADER_SIZE = 22;

    public int seqNum;
    public int ackNum;
    public long timestamp;
    public int dataLen;
    public boolean syn;
    public boolean fin;
    public boolean ack;
    public byte[] data;

    public byte[] toBytes() {
        int total = HEADER_SIZE + (data != null ? data.length : 0);
        byte[] bytes = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.putInt(seqNum);
        buf.putInt(ackNum);
        buf.putLong(timestamp);
        int lenFlags = (dataLen << 3) | (syn ? 4 : 0) | (fin ? 2 : 0) | (ack ? 1 : 0);
        buf.putInt(lenFlags);
        buf.putShort((short) 0);
        if (data != null) buf.put(data);
        short cs = ChecksumUtil.compute(bytes, total);
        bytes[20] = (byte) ((cs >> 8) & 0xFF);
        bytes[21] = (byte) (cs & 0xFF);
        return bytes;
    }

    public static Segment fromBytes(byte[] bytes, int len) {
        Segment seg = new Segment();
        ByteBuffer buf = ByteBuffer.wrap(bytes, 0, len);
        seg.seqNum = buf.getInt();
        seg.ackNum = buf.getInt();
        seg.timestamp = buf.getLong();
        int lenFlags = buf.getInt();
        seg.dataLen = lenFlags >>> 3;
        seg.syn = (lenFlags & 4) != 0;
        seg.fin = (lenFlags & 2) != 0;
        seg.ack = (lenFlags & 1) != 0;
        // skip checksum
        buf.getShort(); 
        int dlen = len - HEADER_SIZE;
        if (dlen > 0) {
            seg.data = new byte[dlen];
            buf.get(seg.data);
        }
        return seg;
    }
}
