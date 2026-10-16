package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.domain.wellknown.WellKnownFile;
import fr.enimaloc.catapult.repository.wellknown.WellKnownFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** The well-known files the admin configures and catapult-web serves (see {@link WellKnownPaths}). */
@Service
public class WellKnownFileService {
    private final WellKnownFileRepository repository;
    private final Clock clock;

    @Autowired
    public WellKnownFileService(WellKnownFileRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WellKnownFileService(WellKnownFileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<WellKnownFileDto> list() {
        return repository.findAllByOrderByLocationAscPathAsc().stream().map(WellKnownFileService::summary).toList();
    }

    public WellKnownFileDetailDto get(long id) {
        return detail(existing(id));
    }

    public WellKnownFileDetailDto create(WellKnownFileRequest request) {
        validate(request);
        if (repository.findByLocationAndPath(request.location(), request.path()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Path already configured");
        }
        return detail(repository.save(fill(new WellKnownFile(), request)));
    }

    public WellKnownFileDetailDto update(long id, WellKnownFileRequest request) {
        WellKnownFile file = existing(id);
        validate(request);
        repository.findByLocationAndPath(request.location(), request.path())
                .filter(other -> !other.getId().equals(file.getId()))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Path already configured");
                });
        return detail(repository.save(fill(file, request)));
    }

    public void delete(long id) {
        repository.delete(existing(id));
    }

    /** The file served at {@code path}; empty when there is none or the path breaks the rules. */
    public Optional<WellKnownFile> find(WellKnownLocation location, String path) {
        if (!WellKnownPaths.valid(location, path)) return Optional.empty();
        return repository.findByLocationAndPath(location, path);
    }

    private WellKnownFile existing(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown well-known file"));
    }

    private static void validate(WellKnownFileRequest request) {
        if (!WellKnownPaths.valid(request.location(), request.path())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        String type = request.contentType();
        if (type == null || type.isBlank() || type.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid content type");
        }
        try {
            MediaType.parseMediaType(type);
        } catch (InvalidMediaTypeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid content type");
        }
        if (request.content() == null
                || request.content().getBytes(StandardCharsets.UTF_8).length > WellKnownPaths.MAX_CONTENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content missing or too large");
        }
    }

    private WellKnownFile fill(WellKnownFile file, WellKnownFileRequest request) {
        file.setLocation(request.location());
        file.setPath(request.path());
        file.setContentType(request.contentType().trim());
        file.setContent(request.content());
        file.setUpdatedAt(clock.instant());
        return file;
    }

    private static WellKnownFileDto summary(WellKnownFile f) {
        return new WellKnownFileDto(f.getId(), f.getLocation(), f.getPath(), f.getContentType(),
                f.getContent().getBytes(StandardCharsets.UTF_8).length, f.getUpdatedAt());
    }

    private static WellKnownFileDetailDto detail(WellKnownFile f) {
        return new WellKnownFileDetailDto(f.getId(), f.getLocation(), f.getPath(), f.getContentType(),
                f.getContent(), f.getUpdatedAt());
    }
}
