package fr.esportline.catapult.web;

import fr.esportline.catapult.service.AdminCclService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@Controller
@RequestMapping("/admin/ccl")
@RequiredArgsConstructor
public class AdminCclController {

    private final AdminCclService adminCclService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("ccls", adminCclService.getAllCcls());
        model.addAttribute("igdbDescriptors", adminCclService.getAllIgdbDescriptors());
        return "admin/ccl";
    }

    @PostMapping("/refresh")
    public String refresh() {
        adminCclService.refreshFromApi();
        return "redirect:/admin/ccl";
    }

    @PostMapping("/{cclId}/mappings")
    public String saveMappings(@PathVariable String cclId,
                               @RequestParam(required = false) Set<Long> igdbCategoryIds) {
        adminCclService.saveMappings(cclId, igdbCategoryIds == null ? Set.of() : igdbCategoryIds);
        return "redirect:/admin/ccl";
    }
}
