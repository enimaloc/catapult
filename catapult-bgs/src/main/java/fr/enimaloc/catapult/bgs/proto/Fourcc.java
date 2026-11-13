package fr.enimaloc.catapult.bgs.proto;

import java.nio.charset.StandardCharsets;

/** BGS title ids and programs are big-endian fourcc codes (4288624 = "App", 1465140039 = "WTCG"). */
public final class Fourcc {

    private Fourcc() {
    }

    /** The code of a title id, without its leading zero bytes; "0" for an id of 0. */
    public static String of(int titleId) {
        StringBuilder sb = new StringBuilder(4);
        for (int shift = 24; shift >= 0; shift -= 8) {
            int b = (titleId >>> shift) & 0xFF;
            if (b != 0 || sb.length() > 0) {
                sb.append((char) b);
            }
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    public static int toId(String fourcc) {
        byte[] bytes = fourcc.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length == 0 || bytes.length > 4) {
            throw new IllegalArgumentException("A fourcc is 1 to 4 characters: " + fourcc);
        }
        int id = 0;
        for (byte b : bytes) {
            id = (id << 8) | (b & 0xFF);
        }
        return id;
    }
}
