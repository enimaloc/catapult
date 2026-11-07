package fr.enimaloc.catapult.simulation.panel;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The {@code mock} profile's control panel page and its two assets, read from the classpath's {@code mock-panel/}
 * (not {@code static/}, which any profile would serve). A 404 unless the client is on the loopback and addresses
 * catapult-api by a loopback name ({@link MockPanelAccess}). Only with the simulation on.
 */
@Controller
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
@DependsOn("mockGuard")
public class MockPanelController {

    static final String PATH = "/mock";
    private static final String ASSETS = "mock-panel/";

    @GetMapping(PATH)
    public ResponseEntity<byte[]> page(HttpServletRequest request) {
        return asset(request, "index.html", MediaType.TEXT_HTML);
    }

    @GetMapping(PATH + "/panel.js")
    public ResponseEntity<byte[]> script(HttpServletRequest request) {
        return asset(request, "panel.js", new MediaType("text", "javascript"));
    }

    @GetMapping(PATH + "/panel.css")
    public ResponseEntity<byte[]> style(HttpServletRequest request) {
        return asset(request, "panel.css", new MediaType("text", "css"));
    }

    private static ResponseEntity<byte[]> asset(HttpServletRequest request, String name, MediaType type) {
        if (!MockPanelAccess.allowed(request)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try (InputStream in = new ClassPathResource(ASSETS + name).getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(type, StandardCharsets.UTF_8))
                    .cacheControl(CacheControl.noStore())
                    // The page's own script and style only: nothing inline, nothing from elsewhere. Avatars are
                    // the exception: Twitch's CDN or any URL typed in the panel
                    .header("Content-Security-Policy", "default-src 'self'; img-src 'self' https:")
                    .body(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("The mock panel's " + name + " is missing from the classpath", e);
        }
    }
}
