package fr.enimaloc.catapult.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The page a visitor gets while catapult-api can't be reached (maintenance.html, self-contained: it
 * needs nothing else from a server that may be half down).
 */
final class MaintenancePage {
    static final String RESOURCE = "/maintenance.html";

    private MaintenancePage() {
    }

    static byte[] bytes() {
        try (InputStream in = MaintenancePage.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
