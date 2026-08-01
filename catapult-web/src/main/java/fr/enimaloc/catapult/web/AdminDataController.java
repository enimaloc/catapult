package fr.enimaloc.catapult.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/data")
@RequiredArgsConstructor
public class AdminDataController {

    private final ApiClient apiClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityPage(int number, int totalPages, long totalElements, List<Map<String, Object>> content) {
        public boolean first() { return number == 0; }
        public boolean last() { return number >= totalPages - 1; }
    }

    @GetMapping
    public String reposPage(Model model) {
        List<?> repos = apiClient.get("/api/admin/data", new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        model.addAttribute("repos", repos != null ? repos : List.of());
        return "admin/data";
    }

    @GetMapping("/{repo}")
    public String entityListPage(@PathVariable String repo,
                                  @RequestParam(required = false, defaultValue = "") String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(required = false) String pickFor,
                                  @RequestParam(required = false) String returnTo,
                                  Model model) {
        EntityPage entities = apiClient.get("/api/admin/data/{repo}?q={q}&page={page}&size=25",
                new ParameterizedTypeReference<EntityPage>() {}, repo, q, page);
        model.addAttribute("repo", repo);
        model.addAttribute("entities", entities != null ? entities : new EntityPage(0, 0, 0, List.of()));
        model.addAttribute("q", q);
        model.addAttribute("pickFor", pickFor);
        // Open-redirect guard: returnTo is attacker-controllable (plain request
        // param) and is used as the base of a link target in
        // data-entity-list.html. Only accept it when it points at an internal
        // /admin/ path; otherwise drop it so the "Select" / pagination links
        // fall back to not carrying a (potentially off-site) return target.
        model.addAttribute("returnTo", isSafeReturnTo(returnTo) ? returnTo : null);
        return "admin/data-entity-list";
    }

    private static boolean isSafeReturnTo(String returnTo) {
        return returnTo != null && returnTo.startsWith("/admin/");
    }

    @GetMapping("/{repo}/new")
    public String newEntityPage(@PathVariable String repo, Model model) {
        model.addAttribute("repo", repo);
        model.addAttribute("entityId", null);
        // Seed with an "id" key (empty value) so the generic form loop below
        // renders an id input on the create form — required for natural-key
        // entities (e.g. TwDefinition) whose @Id has no @GeneratedValue and
        // must be assigned manually. See task-8 brief deviation notes.
        model.addAttribute("entity", new java.util.LinkedHashMap<>(Map.of("id", "")));
        return "admin/data-entity-form";
    }

    @PostMapping("/{repo}/new")
    public String createEntity(@PathVariable String repo, @RequestParam Map<String, String> form) {
        apiClient.post("/api/admin/data/{repo}", stripBlankId(stripCsrf(form)), repo);
        return "redirect:/admin/data/" + repo;
    }

    @GetMapping("/{repo}/{id}")
    public String entityDetailPage(@PathVariable String repo, @PathVariable String id, Model model) {
        Map<String, Object> entity = apiClient.get("/api/admin/data/{repo}/{id}",
                new ParameterizedTypeReference<Map<String, Object>>() {}, repo, id);
        model.addAttribute("repo", repo);
        model.addAttribute("entityId", id);
        model.addAttribute("entity", entity != null ? entity : Map.of());
        return "admin/data-entity-form";
    }

    @PostMapping("/{repo}/{id}")
    public String updateEntity(@PathVariable String repo, @PathVariable String id, @RequestParam Map<String, String> form) {
        apiClient.put("/api/admin/data/{repo}/{id}", stripCsrf(form), repo, id);
        return "redirect:/admin/data/" + repo + "/" + id;
    }

    @PostMapping("/{repo}/{id}/delete")
    public String deleteEntity(@PathVariable String repo, @PathVariable String id) {
        apiClient.delete("/api/admin/data/{repo}/{id}", repo, id);
        return "redirect:/admin/data/" + repo;
    }

    private static Map<String, String> stripCsrf(Map<String, String> form) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(form);
        copy.remove("_csrf");
        return copy;
    }

    /**
     * The create form always renders an "id" input (needed for natural-key
     * entities with no {@code @GeneratedValue}), but for the more common
     * generated-id entities the user is expected to leave it blank. If we
     * forwarded {@code id=""} as-is, the API's {@code applyFields} would try
     * to coerce the empty string into the id type (UUID/Long/...) and fail
     * with a 400 that {@link ApiClient} silently swallows — the row would
     * never be created but the controller would still redirect as if it had
     * been. Dropping a blank id here means it's simply never submitted, so
     * the server assigns one normally, exactly as if the field didn't exist.
     */
    private static Map<String, String> stripBlankId(Map<String, String> form) {
        String id = form.get("id");
        if (id != null && id.isBlank()) {
            Map<String, String> copy = new java.util.LinkedHashMap<>(form);
            copy.remove("id");
            return copy;
        }
        return form;
    }
}
