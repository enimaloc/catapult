package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.IntSupplier;

/**
 * The admin Well-known tab's actions, forwarded to catapult-api with the session's JWT and answered
 * with its status (502 when unreachable); a change is served at once (the public cache is emptied).
 */
@RestController
@RequestMapping("/admin/well-known")
@RequiredArgsConstructor
public class AdminWellKnownController {
    private final ApiService apiService;
    private final ModelFiller filler;
    private final WellKnownCache cache;

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody WellKnownFileRequest body) {
        return relay(() -> apiService.adminCreateWellKnownFile(body));
    }

    @PostMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody WellKnownFileRequest body) {
        return relay(() -> apiService.adminUpdateWellKnownFile(id, body));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return relay(() -> apiService.adminDeleteWellKnownFile(id));
    }

    private ResponseEntity<Void> relay(IntSupplier action) {
        AdminAccess.require(filler);
        int status = action.getAsInt();
        if (status >= 200 && status < 300) cache.clear();
        return ResponseEntity.status(status).build();
    }
}
