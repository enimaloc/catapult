package fr.enimaloc.catapult.bgs.proto;

import java.nio.charset.StandardCharsets;

/** 32-bit FNV-1a, the hash BGS uses to identify a service on the wire. */
public final class Fnv1a {

    private static final int OFFSET_BASIS = 0x811C9DC5;
    private static final int PRIME = 0x01000193;

    private Fnv1a() {
    }

    public static int hash(String s) {
        int h = OFFSET_BASIS;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= PRIME;
        }
        return h;
    }
}
