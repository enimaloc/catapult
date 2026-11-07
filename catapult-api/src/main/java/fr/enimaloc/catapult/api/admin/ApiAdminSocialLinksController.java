package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkOrderRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.service.social.SocialLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The admin page's social link changes; the visible ones are read publicly through {@link fr.enimaloc.catapult.api.ApiSocialLinksController}. */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/social-links")
@RequiredArgsConstructor
public class ApiAdminSocialLinksController {
    private final SocialLinkService service;

    @GetMapping
    public List<SocialLinkDto> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SocialLinkDto create(@RequestBody SocialLinkRequest body) {
        return service.create(body);
    }

    @PutMapping("/order")
    public List<SocialLinkDto> reorder(@RequestBody SocialLinkOrderRequest body) {
        return service.reorder(body);
    }

    @PutMapping("/{id}")
    public SocialLinkDto update(@PathVariable long id, @RequestBody SocialLinkRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
