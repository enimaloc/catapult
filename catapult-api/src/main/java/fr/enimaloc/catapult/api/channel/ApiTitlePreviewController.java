package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.TitlePreviewDto;
import fr.enimaloc.catapult.common.dto.channel.TitlePreviewRequest;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.title.TitlePreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A title field's preview, while it is typed: the title it would send now ({@link TitlePreviewService}). Open to the
 * channel's owner and moderators, refused with feature_disabled outside the {@value StreamTitleService#PREVIEW_KEY}
 * experiment. A POST: the text previewed is the body.
 */
@RestController
@RequestMapping(ApiPaths.USER)
@RequiredArgsConstructor
public class ApiTitlePreviewController {

    private final ApiUserResolver userResolver;
    private final StreamTitleService streamTitleService;
    private final TitlePreviewService titlePreviewService;

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PostMapping("/title/preview")
    public TitlePreviewDto preview(@PathVariable UUID userId, @RequestBody TitlePreviewRequest body) {
        UserAccount channel = userResolver.user(userId);
        if (!streamTitleService.previewAvailableFor(channel)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Title preview not available for this channel");
        }
        return new TitlePreviewDto(titlePreviewService.preview(channel, body).orElse(null));
    }
}
