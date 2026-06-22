package fr.enimaloc.catapult.web.ws.spike;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpikeController {
    @GetMapping("/spike/fragment")
    public String fragment(Model model) {
        model.addAttribute("now", java.time.Instant.now().toString());
        return "spike/fragment";
    }
}
