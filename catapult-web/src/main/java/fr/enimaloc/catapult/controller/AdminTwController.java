package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.TwRebuild;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The admin Trigger warnings tab's actions (add a definition, the three rebuilds), forwarded to
 * catapult-api with the session's JWT and answered with its status (201 created, 202 rebuild
 * started; 400, 409, 503; 502 when unreachable). The id and the label are checked here first (400),
 * catapult-api's own refusal being a 500 for some of them. {@code mode} is {@code non-overridden},
 * {@code untouched} or {@code force-pinned}, else 404.
 */
@RestController
@RequestMapping("/admin/tw")
@RequiredArgsConstructor
public class AdminTwController {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final int LABEL_MAX = 80;

    private final ApiService apiService;
    private final ModelFiller filler;

    public record CreateBody(String id, String label, Integer sortOrder) {}

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateBody body) {
        AdminAccess.require(filler);
        String label = body.label() == null ? "" : body.label().trim();
        int sortOrder = body.sortOrder() == null ? 0 : body.sortOrder();
        if (body.id() == null || !ID.matcher(body.id()).matches()
                || label.isEmpty() || label.length() > LABEL_MAX || sortOrder < 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(apiService.adminCreateTw(body.id(), label, sortOrder)).build();
    }

    @PostMapping("/rebuild/{mode}")
    public ResponseEntity<Void> rebuild(@PathVariable String mode) {
        AdminAccess.require(filler);
        TwRebuild rebuild = TwRebuild.of(mode);
        if (rebuild == null) throw new ResponseStatusException(NOT_FOUND);
        return ResponseEntity.status(apiService.adminRebuildTws(rebuild)).build();
    }
}
