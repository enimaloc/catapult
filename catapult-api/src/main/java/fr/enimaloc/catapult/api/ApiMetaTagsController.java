package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.service.meta.MetaTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The configured meta tags, read by catapult-web for the head of its pages (public, as the pages show them). */
@RestController
@RequiredArgsConstructor
public class ApiMetaTagsController {
    private final MetaTagService service;

    @GetMapping("/api/meta-tags")
    public List<MetaTagDto> list() {
        return service.list();
    }
}
