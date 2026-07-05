package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.IgdbRatingDescriptor;
import fr.enimaloc.catapult.domain.TwitchCclDefinition;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/ccl")
@RequiredArgsConstructor
public class ApiAdminCclController {

    private final AdminCclService adminCclService;

    @Autowired(required = false)
    private AdminEventPublisher events;

    @GetMapping
    public CclPageData page() {
        return new CclPageData(adminCclService.getAllCcls(), adminCclService.getAllIgdbDescriptors());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        adminCclService.refreshFromApi();
        if (events != null) {
            events.cclRefreshed(
                    adminCclService.getAllCcls().stream().map(ApiAdminCclController::cclView).toList(),
                    adminCclService.getAllIgdbDescriptors().stream().map(ApiAdminCclController::descriptorView).toList());
        }
    }

    @PostMapping("/{cclId}/mappings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveMappings(@PathVariable String cclId, @RequestBody SaveMappingsRequest body) {
        adminCclService.saveMappings(cclId, body.igdbCategoryIds() == null ? Set.of() : body.igdbCategoryIds());
        if (events != null) {
            adminCclService.getAllCcls().stream()
                    .filter(c -> cclId.equals(c.getId()))
                    .findFirst()
                    .ifPresent(c -> events.cclMappingsUpdated(cclView(c)));
        }
    }

    /** Clean serialisable view of a CCL for events (avoids leaking JPA proxies). */
    private static Map<String, Object> cclView(TwitchCclDefinition c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName() == null ? "" : c.getName());
        m.put("description", c.getDescription() == null ? "" : c.getDescription());
        m.put("mappedDescriptions", new ArrayList<>(c.getMappedDescriptions()));
        return m;
    }

    private static Map<String, Object> descriptorView(IgdbRatingDescriptor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("description", d.getDescription() == null ? "" : d.getDescription());
        return m;
    }

    public record CclPageData(List<TwitchCclDefinition> ccls, List<IgdbRatingDescriptor> igdbDescriptors) {}

    public record SaveMappingsRequest(Set<Long> igdbCategoryIds) {}
}
