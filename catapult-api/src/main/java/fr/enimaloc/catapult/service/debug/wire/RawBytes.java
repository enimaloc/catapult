package fr.enimaloc.catapult.service.debug.wire;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Helpers over the raw bytes of a binary exchange: hex rendering and identifier search. */
public final class RawBytes {

    private static final HexFormat HEX = HexFormat.of();

    private RawBytes() {
    }

    /** Lowercase hex without separator, or null for null. */
    public static String hex(byte[] raw) {
        return raw == null ? null : HEX.formatHex(raw);
    }

    /**
     * Whether {@code raw} holds {@code value}: as UTF-8 text (ASCII letters matched case-insensitively) and, when
     * {@code value} is an unsigned number, as a protobuf varint or an 8-byte little-endian fixed64.
     */
    public static boolean contains(byte[] raw, String value) {
        if (raw == null || raw.length == 0 || value == null || value.isEmpty()) {
            return false;
        }
        if (indexOfIgnoreCase(raw, value.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        long number;
        try {
            number = Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            return false;
        }
        return indexOf(raw, varint(number)) || indexOf(raw,
                ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(number).array());
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static boolean indexOf(byte[] haystack, byte[] needle) {
        return search(haystack, needle, false);
    }

    private static boolean indexOfIgnoreCase(byte[] haystack, byte[] needle) {
        return search(haystack, needle, true);
    }

    private static boolean search(byte[] haystack, byte[] needle, boolean ignoreCase) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                byte a = haystack[i + j];
                byte b = needle[j];
                if (a != b && !(ignoreCase && lower(a) == lower(b))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int lower(byte b) {
        return b >= 'A' && b <= 'Z' ? b + 32 : b;
    }
}
