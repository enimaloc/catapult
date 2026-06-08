package fr.enimaloc.catapult.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AppController {

    @GetMapping({"/app", "/dashboard", "/settings"})
    public String redirectToChannels() {
        return "redirect:/channels";
    }
}
