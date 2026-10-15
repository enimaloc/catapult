package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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
