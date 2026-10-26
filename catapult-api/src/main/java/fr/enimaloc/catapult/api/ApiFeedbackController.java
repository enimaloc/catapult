package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.SubmitRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.feedback.FeedbackSubmission;
import fr.enimaloc.catapult.service.feedback.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(ApiPaths.USER + "/feedback")
@RequiredArgsConstructor
public class ApiFeedbackController {

    private final FeedbackService feedback;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public List<FeedbackSubmission> list(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        return feedback.list(user);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackSubmission submit(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @RequestBody SubmitRequest body) {
        if (body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
        }

        FeedbackSubmission.Type feedbackType;
        try {
            feedbackType = FeedbackSubmission.Type.valueOf(body.type().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type");
        }

        UserAccount user = userResolver.viewer(jwt);
        return feedback.submit(user, body.title(), feedbackType, body.description());
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping("/{id}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UserAccount user = userResolver.viewer(jwt);
        feedback.unsubscribe(user, id);
    }
}
