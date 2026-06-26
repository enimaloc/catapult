package fr.enimaloc.catapult.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class AdminConfigPageController {

    @GetMapping
    public String page() {
        return "admin/config";
    }
}
