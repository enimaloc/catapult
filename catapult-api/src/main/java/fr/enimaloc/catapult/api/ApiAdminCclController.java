package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.IgdbRatingDescriptor;
import fr.enimaloc.catapult.domain.TwitchCclDefinition;
import fr.enimaloc.catapult.service.AdminCclService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/ccl")
@RequiredArgsConstructor
public class ApiAdminCclController {

    private final AdminCclService adminCclService;

    @GetMapping
    public CclPageData page() {
        return new CclPageData(adminCclService.getAllCcls(), adminCclService.getAllIgdbDescriptors());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        adminCclService.refreshFromApi();
    }

    @PostMapping("/{cclId}/mappings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveMappings(@PathVariable String cclId, @RequestBody SaveMappingsRequest body) {
        adminCclService.saveMappings(cclId, body.igdbCategoryIds() == null ? Set.of() : body.igdbCategoryIds());
    }

    public record CclPageData(List<TwitchCclDefinition> ccls, List<IgdbRatingDescriptor> igdbDescriptors) {}

    public record SaveMappingsRequest(Set<Long> igdbCategoryIds) {}
}
