package fr.enimaloc.catapult.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        if ("not_whitelisted".equals(error)) {
            model.addAttribute("loginError", "not_whitelisted");
        } else if (error != null) {
            model.addAttribute("loginError", "generic");
        }
        return "login";
    }
}
