package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownHeader;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/**
 * The admin tabs admin-tabs.js loads when first shown (and their sub-views and dialogs), each
 * refused, like the admin page itself, to anyone but an admin.
 */
@Controller
@RequestMapping("/spa/admin")
@RequiredArgsConstructor
public class AdminTabsFragmentController {
    private final ModelFiller filler;
    private final ApiService apiService;
    private final BroadcastState broadcastState;

    /** The members list, for the way back from a member's targeting; the page itself comes with it already. */
    @GetMapping("/members")
    String members(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            filler.admin(model);
            return "pages/admin-tab/members :: content";
        });
    }

    /** One member's targeting: custom flags and groups, with the groups still to join; 404 when the member is unknown. */
    @GetMapping("/members/{id}")
    String memberTargeting(Model model, HttpServletResponse response, @PathVariable UUID id) {
        return guarded(model, response, () -> {
            AdminMemberSummaryDto member = apiService.adminMember(id);
            if (member == null) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return "pages/admin-tab/members :: missing";
            }
            List<GroupSummaryDto> groups = apiService.adminGroups();
            MemberTargetingDto targeting = apiService.adminMemberTargeting(id);
            model.addAttribute("member", member);
            model.addAttribute("targeting", targeting);
            model.addAttribute("groups", groups == null ? List.of() : groups);
            model.addAttribute("groupIds", groups == null ? Map.of()
                    : groups.stream().collect(Collectors.toMap(GroupSummaryDto::key, GroupSummaryDto::id, (a, b) -> a)));
            model.addAttribute("joinable", groups == null || targeting == null ? List.of()
                    : groups.stream().filter(g -> !targeting.groupKeys().contains(g.key())).toList());
            return "pages/admin-tab/members :: targeting";
        });
    }

    @GetMapping("/ccl")
    String ccl(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("ccl", apiService.adminCcl());
            return "pages/admin-tab/ccl :: content";
        });
    }

    @GetMapping("/caches")
    String caches(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("caches", apiService.adminCaches());
            return "pages/admin-tab/caches :: list";
        });
    }

    @GetMapping("/caches/{name}")
    String cacheEntries(Model model, HttpServletResponse response, @PathVariable String name,
                        @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return guarded(model, response, () -> {
            List<CacheSummaryDto> caches = apiService.adminCaches();
            model.addAttribute("name", name);
            model.addAttribute("q", q == null ? "" : q);
            model.addAttribute("deletable", caches != null
                    && caches.stream().anyMatch(c -> c.name().equals(name) && c.deletable()));
            model.addAttribute("entries", apiService.adminCacheEntries(name, q, page));
            return "pages/admin-tab/caches :: entries";
        });
    }

    @GetMapping("/caches/{name}/entry")
    String cacheEntry(Model model, HttpServletResponse response, @PathVariable String name, @RequestParam String key) {
        return guarded(model, response, () -> {
            model.addAttribute("key", key);
            model.addAttribute("entry", AdminCacheEntryView.of(apiService.adminCacheEntry(name, key)));
            return "pages/admin-tab/caches :: entry";
        });
    }

    @GetMapping("/data")
    String data(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("repos", apiService.adminDataRepos());
            return "pages/admin-tab/data :: repos";
        });
    }

    @GetMapping("/data/{repo}")
    String dataRows(Model model, HttpServletResponse response, @PathVariable String repo,
                    @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return guarded(model, response, () -> {
            AdminPage<Map<String, Object>> rows = apiService.adminDataRows(repo, q, page);
            model.addAttribute("repo", repo);
            model.addAttribute("q", q == null ? "" : q);
            model.addAttribute("rows", rows);
            model.addAttribute("table", rows == null ? null : AdminDataViews.table(rows.content()));
            return "pages/admin-tab/data :: rows";
        });
    }

    /** A row's edit dialog; without an id, a new row's, laid out on the repository's first row. */
    @GetMapping("/data/{repo}/row")
    String dataRow(Model model, HttpServletResponse response, @PathVariable String repo,
                   @RequestParam(required = false) String id) {
        return guarded(model, response, () -> {
            Map<String, Object> row = id == null ? template(repo) : apiService.adminDataRow(repo, id);
            if (row == null) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return "pages/admin-tab/data :: missing";
            }
            model.addAttribute("form", AdminDataViews.form(repo, id, row));
            return "pages/admin-tab/data :: row";
        });
    }

    /** The repository's first row, to lay a new one out on; just an id when it has none. */
    private Map<String, Object> template(String repo) {
        AdminPage<Map<String, Object>> rows = apiService.adminDataRows(repo, null, 0);
        if (rows == null) return null;
        return rows.content().isEmpty() ? Map.of("id", "") : rows.content().getFirst();
    }

    @GetMapping("/meta")
    String wellKnown(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("metaTags", apiService.metaTags());
            List<WellKnownFileDto> files = apiService.adminWellKnownFiles();
            model.addAttribute("files", files);
            model.addAttribute("registry", apiService.adminIanaRegistry());
            model.addAttribute("configured", files == null ? Set.of() : AdminWellKnownViews.configured(files));
            return "pages/admin-tab/meta :: content";
        });
    }

    /** A file's dialog; without an id, a new file's, prefilled from the registry's {@code path} when given. */
    @GetMapping("/meta/file")
    String wellKnownFile(Model model, HttpServletResponse response, @RequestParam(required = false) Long id,
                         @RequestParam(required = false) WellKnownLocation location,
                         @RequestParam(required = false) String path) {
        return guarded(model, response, () -> {
            WellKnownFileDetailDto file;
            String preset = null;
            if (id != null) {
                file = apiService.adminWellKnownFile(id);
                if (file == null) {
                    response.setStatus(HttpStatus.NOT_FOUND.value());
                    return "pages/admin-tab/meta :: missing";
                }
                // a redirect or an empty file has no content to edit: it opens on the Response tab
                if (file.status() >= 300 && file.status() < 400 || file.content() == null || file.content().isEmpty()) {
                    preset = "redirect";
                }
            } else {
                String p = path == null ? "" : path;
                WellKnownLocation where = location == null ? WellKnownLocation.WELL_KNOWN : location;
                boolean redirect = where == WellKnownLocation.WELL_KNOWN && "change-password".equals(p);
                if (redirect) preset = "redirect";
                file = new WellKnownFileDetailDto(0, where, p, redirect ? 302 : 200,
                        redirect ? List.of(new WellKnownHeader("Location", "")) : List.of(),
                        AdminWellKnownViews.contentType(p), "", null);
            }
            model.addAttribute("file", file);
            model.addAttribute("preset", preset);
            model.addAttribute("url", AdminWellKnownViews.url(file.location(), file.path()));
            model.addAttribute("creating", id == null);
            return "pages/admin-tab/meta :: file";
        });
    }

    /** SchemaStore's schema for a well-known path, for the dialog's generated form; 404 when none. */
    @GetMapping(value = "/meta/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<String> wellKnownSchema(@RequestParam String path) {
        int refused = AdminAccess.refusal(filler);
        if (refused != 0) return ResponseEntity.status(refused).build();
        String schema = apiService.adminWellKnownSchema(path);
        return schema == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(schema);
    }

    @GetMapping("/notifications")
    String notifications(Model model, HttpServletResponse response, @RequestParam(defaultValue = "0") int page) {
        return guarded(model, response, () -> {
            AdminMembersPage members = apiService.adminMembers();
            model.addAttribute("notifications", apiService.adminNotifications(page));
            model.addAttribute("members", members == null ? null : members.members());
            return "pages/admin-tab/notifications :: content";
        });
    }

    /** The proposals of one status (PENDING by default, also for any unknown value) with the status chips. */
    @GetMapping("/dtdd-mapping")
    String dtddMapping(Model model, HttpServletResponse response, @RequestParam(defaultValue = "PENDING") String status) {
        return guarded(model, response, () -> {
            String shown = "APPROVED".equals(status) || "REJECTED".equals(status) ? status : "PENDING";
            model.addAttribute("status", shown);
            model.addAttribute("proposals", apiService.adminDtddProposals(shown));
            return "pages/admin-tab/dtdd-mapping :: content";
        });
    }

    @GetMapping("/groups")
    String groups(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("groups", apiService.adminGroups());
            return "pages/admin-tab/groups :: content";
        });
    }

    @GetMapping("/whitelist")
    String whitelist(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("whitelist", apiService.adminWhitelist());
            return "pages/admin-tab/whitelist :: content";
        });
    }

    /** The trigger warning definitions. */
    @GetMapping("/tw")
    String tw(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("definitions", apiService.adminTws());
            return "pages/admin-tab/tw :: content";
        });
    }

    /** One definition's Steam keywords and the tester; the missing fragment (404) when it is unknown. */
    @GetMapping("/tw/{id}")
    String twKeywords(Model model, HttpServletResponse response, @PathVariable String id) {
        return guarded(model, response, () -> {
            TwDefinitionDto definition = apiService.adminTw(id);
            if (definition == null) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return "pages/admin-tab/tw :: missing";
            }
            model.addAttribute("definition", definition);
            model.addAttribute("keywords", apiService.adminTwKeywords(id));
            return "pages/admin-tab/tw :: keywords";
        });
    }

    /** Steam and DTDD keys cards, then the Minecraft service accounts card. */
    @GetMapping("/keys")
    String keys(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("steam", apiService.adminApiKeys(ApiKeyProvider.STEAM));
            model.addAttribute("dtdd", apiService.adminApiKeys(ApiKeyProvider.DTDD));
            model.addAttribute("minecraft", apiService.adminMinecraftAccounts());
            return "pages/admin-tab/keys :: content";
        });
    }

    @GetMapping("/broadcast")
    String broadcast(Model model, HttpServletResponse response) {
        return guarded(model, response, () -> {
            model.addAttribute("maintenance", broadcastState.maintenance().orElse(null));
            return "pages/admin-tab/broadcast :: content";
        });
    }

    /** {@code view}'s fragment for an admin; the 401 / 403 error fragment for anyone else. */
    private String guarded(Model model, HttpServletResponse response, Supplier<String> view) {
        int refused = AdminAccess.refusal(filler);
        if (refused != 0) {
            response.setStatus(refused);
            filler.error(model, refused);
            return "pages/error :: error";
        }
        return view.get();
    }
}
