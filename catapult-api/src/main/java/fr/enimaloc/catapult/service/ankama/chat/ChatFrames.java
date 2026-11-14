package fr.enimaloc.catapult.service.ankama.chat;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wire format of the Ankama launcher chat: the outer length-prefixed frame and the protobuf {@code transport.Frame}
 * carried by data frames. Protobuf is hand-coded with {@link CodedInputStream}/{@link CodedOutputStream}.
 */
public final class ChatFrames {

    public static final int KIND_DATA = 0;
    public static final int KIND_PING = 1;
    public static final int KIND_PONG = 2;
    public static final int KIND_HEARTBEAT = 3;

    /** Length (4 bytes) plus kind (1 byte). */
    private static final int HEADER_SIZE = 5;
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    private ChatFrames() {
    }

    public record Outer(int kind, byte[] body) {
    }

    public record Payload(int id, byte[] data) {
    }

    public sealed interface Frame permits Request, Response, Event {
    }

    public record Request(int correlationId, Payload payload) implements Frame {
    }

    /** {@code payload} is null when the server answers with a bare status. */
    public record Response(int correlationId, int status, Payload payload) implements Frame {
    }

    public record Event(Payload payload) implements Frame {
    }

    public static void write(OutputStream out, int kind, byte[] body) throws IOException {
        int length = HEADER_SIZE + body.length;
        byte[] frame = new byte[length];
        frame[0] = (byte) (length >>> 24);
        frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        frame[4] = (byte) kind;
        System.arraycopy(body, 0, frame, HEADER_SIZE, body.length);
        out.write(frame);
        out.flush();
    }

    /** @throws EOFException when the stream is closed, even in the middle of a frame */
    public static Outer read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = data.readInt();
        if (length < HEADER_SIZE || length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid chat frame length " + length);
        }
        int kind = data.readUnsignedByte();
        byte[] body = new byte[length - HEADER_SIZE];
        data.readFully(body);
        return new Outer(kind, body);
    }

    public static byte[] encode(Frame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        switch (frame) {
            case Request request -> {
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                CodedOutputStream o = CodedOutputStream.newInstance(inner);
                o.writeInt32(1, request.correlationId());
                o.writeByteArray(2, encodePayload(request.payload()));
                o.flush();
                out.writeByteArray(1, inner.toByteArray());
            }
            case Response response -> {
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                CodedOutputStream o = CodedOutputStream.newInstance(inner);
                o.writeInt32(1, response.correlationId());
                o.writeInt32(2, response.status());
                if (response.payload() != null) {
                    o.writeByteArray(3, encodePayload(response.payload()));
                }
                o.flush();
                out.writeByteArray(2, inner.toByteArray());
            }
            case Event event -> out.writeByteArray(3, encodePayload(event.payload()));
        }
        out.flush();
        return bytes.toByteArray();
    }

    /** @throws IOException when the frame is malformed or carries none of request, response or event */
    public static Frame decode(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        Frame frame = null;
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> frame = decodeRequest(in.readByteArray());
                case 2 -> frame = decodeResponse(in.readByteArray());
                case 3 -> frame = new Event(decodePayload(in.readByteArray()));
                default -> in.skipField(tag);
            }
        }
        if (frame == null) {
            throw new IOException("Chat frame carries no request, response or event");
        }
        return frame;
    }

    private static byte[] encodePayload(Payload payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeInt32(1, payload.id());
        out.writeByteArray(2, payload.data());
        out.flush();
        return bytes.toByteArray();
    }

    private static Request decodeRequest(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int correlationId = 0;
        Payload payload = new Payload(0, new byte[0]);
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> correlationId = in.readInt32();
                case 2 -> payload = decodePayload(in.readByteArray());
                default -> in.skipField(tag);
            }
        }
        return new Request(correlationId, payload);
    }

    private static Response decodeResponse(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int correlationId = 0;
        int status = 0;
        Payload payload = null;
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> correlationId = in.readInt32();
                case 2 -> status = in.readInt32();
                case 3 -> payload = decodePayload(in.readByteArray());
                default -> in.skipField(tag);
            }
        }
        return new Response(correlationId, status, payload);
    }

    private static Payload decodePayload(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int id = 0;
        byte[] data = new byte[0];
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> id = in.readInt32();
                case 2 -> data = in.readByteArray();
                default -> in.skipField(tag);
            }
        }
        return new Payload(id, data);
    }
}
