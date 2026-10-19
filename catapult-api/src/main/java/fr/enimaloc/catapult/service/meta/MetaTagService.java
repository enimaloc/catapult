package fr.enimaloc.catapult.service.meta;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRules;
import fr.enimaloc.catapult.domain.meta.MetaTag;
import fr.enimaloc.catapult.repository.meta.MetaTagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;

/**
 * The meta tags the admin configures and catapult-web adds to every page, checked against
 * {@link MetaTagRules}; a key is unique per attribute whatever its case.
 */
@Service
public class MetaTagService {

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
        String problem = MetaTagRules.problem(request);
        if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
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
