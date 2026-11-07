package fr.enimaloc.catapult.service.social;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkOrderRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.domain.social.SocialLink;
import fr.enimaloc.catapult.repository.social.SocialLinkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The social links the admin configures and catapult-web shows in its footer. A url must be
 * {@code https://} (any case for the scheme, nothing before it, no blank or control character), so that a
 * page never links to {@code javascript:} or a plain {@code http:} address; a new link goes last.
 */
@Service
public class SocialLinkService {
    public static final int MAX_URL = 2048;
    public static final int MAX_LABEL = 64;
    private static final String HTTPS = "https://";

    private final SocialLinkRepository repository;

    public SocialLinkService(SocialLinkRepository repository) {
        this.repository = repository;
    }

    /** The links to show: the visible ones, in order. */
    public List<SocialLinkDto> listVisible() {
        return repository.findByVisibleTrueOrderByPositionAscIdAsc().stream().map(SocialLinkService::dto).toList();
    }

    /** Every link, hidden ones included, in order. */
    public List<SocialLinkDto> listAll() {
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(SocialLinkService::dto).toList();
    }

    public SocialLinkDto create(SocialLinkRequest request) {
        validate(request);
        SocialLink link = fill(new SocialLink(), request);
        link.setPosition(repository.findFirstByOrderByPositionDesc().map(last -> last.getPosition() + 1).orElse(0));
        return dto(repository.save(link));
    }

    public SocialLinkDto update(long id, SocialLinkRequest request) {
        SocialLink link = existing(id);
        validate(request);
        return dto(repository.save(fill(link, request)));
    }

    public void delete(long id) {
        repository.delete(existing(id));
    }

    /** Positions the links 0..n-1 in the given order; the list must hold every id exactly once. */
    public List<SocialLinkDto> reorder(SocialLinkOrderRequest request) {
        List<SocialLink> links = repository.findAllByOrderByPositionAscIdAsc();
        List<Long> ids = request == null ? null : request.ids();
        if (ids == null || ids.size() != links.size() || !new HashSet<>(ids).equals(
                links.stream().map(SocialLink::getId).collect(Collectors.toSet()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The ids must be every social link, once");
        }
        Map<Long, SocialLink> byId = links.stream().collect(Collectors.toMap(SocialLink::getId, Function.identity()));
        for (int i = 0; i < ids.size(); i++) byId.get(ids.get(i)).setPosition(i);
        return repository.saveAll(links).stream()
                .sorted(java.util.Comparator.comparingInt(SocialLink::getPosition))
                .map(SocialLinkService::dto).toList();
    }

    private SocialLink existing(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown social link"));
    }

    private static void validate(SocialLinkRequest request) {
        if (request == null || request.platform() == null) bad("Missing platform");
        String url = request.url();
        if (url == null || url.length() > MAX_URL || url.length() <= HTTPS.length()
                || !url.regionMatches(true, 0, HTTPS, 0, HTTPS.length())
                || url.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) {
            bad("Invalid url");
        }
        if (request.label() != null && request.label().length() > MAX_LABEL) bad("Invalid label");
    }

    private static void bad(String problem) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
    }

    private static SocialLink fill(SocialLink link, SocialLinkRequest request) {
        link.setPlatform(request.platform());
        link.setLabel(request.label() == null || request.label().isBlank() ? null : request.label());
        link.setUrl(request.url());
        link.setVisible(request.visible());
        return link;
    }

    private static SocialLinkDto dto(SocialLink link) {
        return new SocialLinkDto(link.getId(), link.getPlatform(), link.getLabel(), link.getUrl(), link.isVisible(),
                link.getPosition());
    }
}
