package fr.enimaloc.catapult.api.internal;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import fr.enimaloc.catapult.service.debug.ClientDebugIngestService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetAccess;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * The OBS and Twitchat exchanges a member's browser saw while the member is in debug mode, relayed by catapult-web:
 * from the member's page (their session) or from their OBS widget (its token alone, the widget having no session).
 * 204 once recorded (entries skipped or not), 409 when the member is not in debug mode (the browser then stops
 * capturing), 400 for a body that is no batch or is over the limits of {@link ClientDebugIngestService#read}.
 */
@RestController
@RequiredArgsConstructor
public class ApiInternalClientDebugController {

    private final ClientDebugIngestService ingestService;
    private final TwitchatWidgetAccess widgetAccess;

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping(ApiPaths.USER + "/debug/client-exchanges")
    public ResponseEntity<Void> record(@PathVariable UUID userId, HttpServletRequest request) {
        return ingest(userId, "page", request);
    }

    /** Open like the widget's other routes: the widget's token is what names its owner. 404 for no enabled widget. */
    @PostMapping(ApiPaths.INTERNAL + "/twitchat-widgets/{widgetId}/debug/client-exchanges")
    public ResponseEntity<Void> recordFromWidget(@PathVariable UUID widgetId, HttpServletRequest request) {
        return widgetAccess.owner(widgetId)
                .map(owner -> ingest(owner, "widget", request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown widget"));
    }

    /** Records the batch in {@code request}'s body for {@code userId}, as captured by {@code source}. */
    private ResponseEntity<Void> ingest(UUID userId, String source, HttpServletRequest request) {
        // Checked first, so that a browser still sending after the debug ended costs no read
        if (!ingestService.inDebug(userId)) {
            throw notInDebug();
        }
        Optional<ClientExchangeBatch> batch;
        try {
            // One byte over the limit is enough to know the body is over it, without reading all of it
            batch = ingestService.read(request.getInputStream().readNBytes(ClientDebugIngestService.MAX_BYTES + 1));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable batch");
        }
        ClientExchangeBatch read = batch.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Not a batch, or over " + ClientDebugIngestService.MAX_ENTRIES + " entries or "
                        + ClientDebugIngestService.MAX_BYTES + " bytes"));
        return switch (ingestService.ingest(userId, new ClientExchangeBatch(source, read.sentAt(), read.entries()))) {
            case RECORDED -> ResponseEntity.noContent().build();
            case NOT_IN_DEBUG -> throw notInDebug();
        };
    }

    private static ResponseStatusException notInDebug() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Not in debug mode");
    }
}
