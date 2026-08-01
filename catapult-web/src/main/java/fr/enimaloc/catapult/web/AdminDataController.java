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
                                  @RequestParam(required = false) String error,
                                  Model model) {
        EntityPage entities = apiClient.get("/api/admin/data/{repo}?q={q}&page={page}&size=25",
                new ParameterizedTypeReference<EntityPage>() {}, repo, q, page);
        model.addAttribute("repo", repo);
        model.addAttribute("entities", entities != null ? entities : new EntityPage(0, 0, 0, List.of()));
        model.addAttribute("q", q);
        model.addAttribute("pickFor", pickFor);
        model.addAttribute("error", error != null);
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
    public String newEntityPage(@PathVariable String repo, @RequestParam(required = false) String error, Model model) {
        model.addAttribute("repo", repo);
        model.addAttribute("entityId", null);
        // Seed with an "id" key (empty value) so the generic form loop below
        // renders an id input on the create form — required for natural-key
        // entities (e.g. TwDefinition) whose @Id has no @GeneratedValue and
        // must be assigned manually. See task-8 brief deviation notes.
        model.addAttribute("entity", new java.util.LinkedHashMap<>(Map.of("id", "")));
        model.addAttribute("error", error != null);
        return "admin/data-entity-form";
    }

    @PostMapping("/{repo}/new")
    public String createEntity(@PathVariable String repo, @RequestParam Map<String, String> form) {
        Map<String, Object> result = postForRow("/api/admin/data/{repo}", stripBlankId(stripCsrf(form)), repo);
        if (!isSuccessRow(result)) {
            return "redirect:/admin/data/" + repo + "/new?error=true";
        }
        return "redirect:/admin/data/" + repo;
    }

    @GetMapping("/{repo}/{id}")
    public String entityDetailPage(@PathVariable String repo, @PathVariable String id,
                                    @RequestParam(required = false) String error, Model model) {
        Map<String, Object> entity = apiClient.get("/api/admin/data/{repo}/{id}",
                new ParameterizedTypeReference<Map<String, Object>>() {}, repo, id);
        model.addAttribute("repo", repo);
        model.addAttribute("entityId", id);
        model.addAttribute("entity", entity != null ? entity : Map.of());
        model.addAttribute("error", error != null);
        return "admin/data-entity-form";
    }

    @PostMapping("/{repo}/{id}")
    public String updateEntity(@PathVariable String repo, @PathVariable String id, @RequestParam Map<String, String> form) {
        Map<String, Object> result = putForRow("/api/admin/data/{repo}/{id}", stripCsrf(form), repo, id);
        if (!isSuccessRow(result)) {
            return "redirect:/admin/data/" + repo + "/" + id + "?error=true";
        }
        return "redirect:/admin/data/" + repo + "/" + id;
    }

    @PostMapping("/{repo}/{id}/delete")
    public String deleteEntity(@PathVariable String repo, @PathVariable String id) {
        apiClient.delete("/api/admin/data/{repo}/{id}", repo, id);
        // apiClient.delete(...) always returns true here (see below), so it can't be used to
        // detect the 409 Conflict that ApiAdminDataController#delete raises on an FK violation.
        // Verify by re-fetching: if the row still exists, the delete did not actually happen.
        Map<String, Object> stillThere = apiClient.get("/api/admin/data/{repo}/{id}",
                new ParameterizedTypeReference<Map<String, Object>>() {}, repo, id);
        if (isSuccessRow(stillThere)) {
            return "redirect:/admin/data/" + repo + "?error=true";
        }
        return "redirect:/admin/data/" + repo;
    }

    /**
     * catapult-api's admin-data endpoints report failures (400/404/409) as plain HTTP 4xx
     * responses. {@link ApiClient}'s {@code RestClient} is built with a
     * {@code defaultStatusHandler(HttpStatusCode::is4xxClientError, ...)} that only logs and
     * does NOT throw (see {@code ApiClient}'s constructor) — that handler is matched before
     * Spring's own default (throwing) status handler, so it fully short-circuits it. As a
     * result {@code ApiClient}'s bodiless {@code boolean put(...)}/{@code boolean delete(...)}
     * and {@code void post(...)} overloads never see an exception for a 4xx response and always
     * report success; they only catch genuine network failures or 5xx (which aren't handled by
     * that custom handler and still throw normally).
     * <p>
     * The one signal that does survive is the response body: {@link ApiAdminDataController}
     * (catapult-api) always returns a row map starting with an {@code "id"} entry on success
     * (see {@code toRowMap}), while a 4xx failure yields a {@code ProblemDetail} body
     * ({@code status}/{@code title}/{@code detail}/{@code type} — never {@code "id"}). So we
     * request the response as a {@code Map} via the generic {@code Class<T>}-returning
     * put/post overloads and treat "no id key" as failure. This is a workaround entirely local
     * to this controller — {@code ApiClient} itself is shared app-wide and is left untouched.
     */
    private static boolean isSuccessRow(Map<String, Object> result) {
        return result != null && result.containsKey("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForRow(String path, Object body, Object... uriVars) {
        return (Map<String, Object>) apiClient.post(path, body, Map.class, uriVars);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> putForRow(String path, Object body, Object... uriVars) {
        return (Map<String, Object>) apiClient.put(path, body, Map.class, uriVars);
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
