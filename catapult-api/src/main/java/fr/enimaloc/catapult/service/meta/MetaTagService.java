package fr.enimaloc.catapult.service.meta;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.domain.meta.MetaTag;
import fr.enimaloc.catapult.repository.meta.MetaTagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The meta tags the admin configures and catapult-web adds to every page. A key is a plain name
 * (letters, digits, {@code : . _ -}, starting with a letter); the ones the site already writes itself
 * are refused, so that a page never carries two conflicting copies; a content is one line.
 */
@Service
public class MetaTagService {
    static final int MAX_KEY = 100;
    static final int MAX_CONTENT = 1000;
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9:._-]*");
    /** Written by catapult-web's index.html itself (compared lowercased). */
    static final Set<String> RESERVED = Set.of("viewport", "description", "robots", "og:type", "og:site_name",
            "og:title", "og:description", "og:url", "twitter:card", "sentry-trace", "baggage");

    private final MetaTagRepository repository;
    private final Clock clock;

    @Autowired
    public MetaTagService(MetaTagRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MetaTagService(MetaTagRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<MetaTagDto> list() {
        return repository.findAllByOrderByAttributeAscKeyAsc().stream().map(MetaTagService::dto).toList();
    }

    public MetaTagDto create(MetaTagRequest request) {
        validate(request);
        if (repository.findByAttributeAndKeyIgnoreCase(request.attribute(), request.key()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Meta tag already configured");
        }
        return dto(repository.save(fill(new MetaTag(), request)));
    }

    public MetaTagDto update(long id, MetaTagRequest request) {
        MetaTag tag = existing(id);
        validate(request);
        repository.findByAttributeAndKeyIgnoreCase(request.attribute(), request.key())
                .filter(other -> !other.getId().equals(tag.getId()))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Meta tag already configured");
                });
        return dto(repository.save(fill(tag, request)));
    }

    public void delete(long id) {
        repository.delete(existing(id));
    }

    private MetaTag existing(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown meta tag"));
    }

    private static void validate(MetaTagRequest request) {
        if (request == null || request.attribute() == null) throw bad("Missing attribute");
        String key = request.key();
        if (key == null || key.length() > MAX_KEY || !KEY.matcher(key).matches()) throw bad("Invalid key");
        if (RESERVED.contains(key.toLowerCase(Locale.ROOT))) throw bad("Reserved key");
        String content = request.content();
        if (content == null || content.length() > MAX_CONTENT) throw bad("Invalid content");
        if (content.chars().anyMatch(Character::isISOControl)) throw bad("Invalid content");
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private MetaTag fill(MetaTag tag, MetaTagRequest request) {
        tag.setAttribute(request.attribute());
        tag.setKey(request.key());
        tag.setContent(request.content());
        tag.setUpdatedAt(clock.instant());
        return tag;
    }

    private static MetaTagDto dto(MetaTag tag) {
        return new MetaTagDto(tag.getId(), tag.getAttribute(), tag.getKey(), tag.getContent(), tag.getUpdatedAt());
    }
}
