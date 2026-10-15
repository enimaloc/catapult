package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
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
