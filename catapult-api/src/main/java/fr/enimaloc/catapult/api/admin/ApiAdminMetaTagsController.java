package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.service.meta.MetaTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The admin page's meta tag changes; the list is read publicly through {@link fr.enimaloc.catapult.api.ApiMetaTagsController}. */
@RestController
@RequestMapping("/api/admin/meta-tags")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminMetaTagsController {
    private final MetaTagService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MetaTagDto create(@RequestBody MetaTagRequest body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public MetaTagDto update(@PathVariable long id, @RequestBody MetaTagRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
