package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class AdminGroupsController {

    private final ApiClient apiClient;

    @GetMapping
    public String groupsPage(Model model) {
        List<GroupSummaryDto> groups = apiClient.get("/api/admin/groups",
                new ParameterizedTypeReference<List<GroupSummaryDto>>() {});
        model.addAttribute("groups", groups != null ? groups : List.of());
        return "admin/groups";
    }

    @PostMapping
    public String createGroup(@RequestParam String key,
                              @RequestParam String name,
                              @RequestParam(required = false) String description) {
        apiClient.post("/api/admin/groups", new CreateGroupBody(key, name, description));
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/rename")
    public String renameGroup(@PathVariable UUID id,
                              @RequestParam String name,
                              @RequestParam(required = false) String description) {
        apiClient.post("/api/admin/groups/{id}/rename", new RenameBody(name, description), id);
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/delete")
    public String deleteGroup(@PathVariable UUID id) {
        apiClient.post("/api/admin/groups/{id}/delete", null, id);
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/members")
    public String addMember(@PathVariable UUID id,
                            @RequestParam String twitchUsername) {
        apiClient.post("/api/admin/groups/{id}/members", new AddMemberBody(twitchUsername), id);
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/members/{userId}/delete")
    public String removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        apiClient.post("/api/admin/groups/{id}/members/{userId}/delete", null, id, userId);
        return "redirect:/admin/groups";
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record GroupSummaryDto(UUID id, String key, String name, String description, int memberCount) {}

    record CreateGroupBody(String key, String name, String description) {}

    record RenameBody(String name, String description) {}

    record AddMemberBody(String twitchUsername) {}
}
