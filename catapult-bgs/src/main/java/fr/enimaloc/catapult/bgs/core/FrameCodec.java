package fr.enimaloc.catapult.bgs.core;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** BGS frame: {@code [uint16 big-endian header length][Header][body of Header.size bytes]}. */
public final class FrameCodec {

    /** Upper bound on a body, to refuse a corrupt size before allocating it. */
    private static final int MAX_BODY = 16 * 1024 * 1024;

    public record Frame(Header header, ByteString body) {
    }

    private FrameCodec() {
    }

    /** Encodes a frame; {@code size} is always set from the body. */
    public static byte[] encode(Header header, ByteString body) {
        Header h = header.toBuilder().setSize(body.size()).build();
        int headerLength = h.getSerializedSize();
        if (headerLength > 0xFFFF) {
            throw new IllegalArgumentException("Header too large: " + headerLength);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + headerLength + body.size());
        out.write(headerLength >>> 8);
        out.write(headerLength);
        out.writeBytes(h.toByteArray());
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** Reads one frame; throws {@link java.io.EOFException} when the stream ends. */
    public static Frame read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int headerLength = data.readUnsignedShort();
        byte[] headerBytes = new byte[headerLength];
        data.readFully(headerBytes);
        Header header = Header.parseFrom(headerBytes);
        int size = header.getSize();
        if (size < 0 || size > MAX_BODY) {
            throw new IOException("Invalid BGS body size: " + Integer.toUnsignedString(size));
        }
        byte[] body = new byte[size];
        data.readFully(body);
        return new Frame(header, ByteString.copyFrom(body));
    }
}
