package fr.enimaloc.catapult.bgs.core;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameCodecTest {

    @Test
    void encodesHeaderLengthThenHeaderThenBody() throws Exception {
        ByteString body = ByteString.copyFromUtf8("hello");
        Header header = Header.newBuilder().setServiceId(0).setServiceHash(0x5869BE8C).setMethodId(9).setToken(3).build();

        byte[] frame = FrameCodec.encode(header, body);

        int headerLength = ByteBuffer.wrap(frame, 0, 2).getShort() & 0xFFFF;
        Header decoded = Header.parseFrom(Arrays.copyOfRange(frame, 2, 2 + headerLength));
        assertThat(decoded.getSize()).isEqualTo(body.size());
        assertThat(decoded.getToken()).isEqualTo(3);
        assertThat(Arrays.copyOfRange(frame, 2 + headerLength, frame.length)).isEqualTo(body.toByteArray());
        assertThat(frame).hasSize(2 + headerLength + body.size());
    }

    @Test
    void readReturnsWhatEncodeProduced() throws Exception {
        Header header = Header.newBuilder().setServiceId(0xFE).setToken(5).setStatus(7).build();
        byte[] two = concat(FrameCodec.encode(header, ByteString.copyFromUtf8("a")),
                FrameCodec.encode(header, ByteString.EMPTY));
        var in = new ByteArrayInputStream(two);

        FrameCodec.Frame first = FrameCodec.read(in);
        FrameCodec.Frame second = FrameCodec.read(in);

        assertThat(first.header().getStatus()).isEqualTo(7);
        assertThat(first.body().toStringUtf8()).isEqualTo("a");
        assertThat(second.body()).isEmpty();
        assertThatThrownBy(() -> FrameCodec.read(in)).isInstanceOf(EOFException.class);
    }

    @Test
    void truncatedBodyIsAnEof() {
        byte[] frame = FrameCodec.encode(Header.newBuilder().setServiceId(0).setToken(0).build(), ByteString.copyFromUtf8("abcdef"));
        byte[] cut = Arrays.copyOf(frame, frame.length - 2);

        assertThatThrownBy(() -> FrameCodec.read(new ByteArrayInputStream(cut))).isInstanceOf(EOFException.class);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
