package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import fr.enimaloc.catapult.common.dto.admin.TwKeywordTestDto;
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

import java.util.List;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The admin Trigger warnings tab's actions (add a definition, the three rebuilds), forwarded to
 * catapult-api with the session's JWT and answered with its status (201 created, 202 rebuild
 * started; 503; 502 when unreachable). A taken id is answered 409 here, from the definitions list, since
 * catapult-api itself fails on a duplicate with a 500. The id and the label are checked here first (400),
 * catapult-api's own refusal being a 500 for some of them. {@code mode} is {@code non-overridden},
 * {@code untouched} or {@code force-pinned}, else 404.
 * <p>
 * A definition's Steam keywords: add, remove and test against a Steam app. A keyword is free text, so it
 * travels in the JSON body (a path can't carry a {@code /} or a {@code %}). Length (2..50), the app id
 * (digits) and the draft are checked here first (400), catapult-api failing with a 500 on them. The test
 * answers the result as JSON, or 502 when catapult-api can't produce one.
 */
@RestController
@RequestMapping("/admin/tw")
@RequiredArgsConstructor
public class AdminTwController {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final int LABEL_MAX = 80;
    private static final int KEYWORD_MIN = 2;
    private static final int KEYWORD_MAX = 50;
    private static final Pattern APP_ID = Pattern.compile("[0-9]{1,12}");

    private final ApiService apiService;
    private final ModelFiller filler;

    public record CreateBody(String id, String label, Integer sortOrder) {}

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateBody body) {
        AdminAccess.require(filler);
        String label = body.label() == null ? "" : body.label().trim();
        int sortOrder = body.sortOrder() == null ? 0 : body.sortOrder();
        if (body.id() == null || !ID.matcher(body.id()).matches()
                || label.isEmpty() || label.length() > LABEL_MAX) {
            return ResponseEntity.badRequest().build();
        }
        List<TwDefinitionDto> existing = apiService.adminTws();
        if (existing != null && existing.stream().anyMatch(d -> d.id().equals(body.id()))) {
            return ResponseEntity.status(409).build();
        }
        return ResponseEntity.status(apiService.adminCreateTw(body.id(), label, sortOrder)).build();
    }

    public record KeywordBody(String keyword) {}

    public record TestBody(String appId, String draftKeyword) {}

    @PostMapping("/{id}/keywords")
    public ResponseEntity<Void> addKeyword(@PathVariable String id, @RequestBody KeywordBody body) {
        AdminAccess.require(filler);
        String keyword = body.keyword() == null ? "" : body.keyword().trim();
        if (keyword.length() < KEYWORD_MIN || keyword.length() > KEYWORD_MAX) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminAddTwKeyword(id, keyword)).build();
    }

    @PostMapping("/{id}/keywords/remove")
    public ResponseEntity<Void> removeKeyword(@PathVariable String id, @RequestBody KeywordBody body) {
        AdminAccess.require(filler);
        if (body.keyword() == null || body.keyword().isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminRemoveTwKeyword(id, body.keyword())).build();
    }

    @PostMapping("/{id}/keywords/test")
    public ResponseEntity<TwKeywordTestDto> testKeyword(@PathVariable String id, @RequestBody TestBody body) {
        AdminAccess.require(filler);
        String draft = body.draftKeyword() == null ? null : body.draftKeyword().trim();
        if (body.appId() == null || !APP_ID.matcher(body.appId()).matches()
                || (draft != null && draft.length() > KEYWORD_MAX)) {
            return ResponseEntity.badRequest().build();
        }
        TwKeywordTestDto result = apiService.adminTestTwKeyword(id, body.appId(), draft == null || draft.isEmpty() ? null : draft);
        return result == null ? ResponseEntity.status(502).build() : ResponseEntity.ok(result);
    }

    @PostMapping("/rebuild/{mode}")
    public ResponseEntity<Void> rebuild(@PathVariable String mode) {
        AdminAccess.require(filler);
        TwRebuild rebuild = TwRebuild.of(mode);
        if (rebuild == null) throw new ResponseStatusException(NOT_FOUND);
        return ResponseEntity.status(apiService.adminRebuildTws(rebuild)).build();
    }
}
