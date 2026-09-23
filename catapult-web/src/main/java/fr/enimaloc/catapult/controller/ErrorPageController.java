package fr.enimaloc.catapult.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
public class ErrorPageController implements ErrorController {
    private final ModelFiller filler;

    @RequestMapping("/error")
    String error(Model model, HttpServletRequest request) {
        filler.error(model, request);

        Object forwardedUri = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (forwardedUri instanceof String uri && uri.startsWith("/spa/")) {
            // Failure while fetching an SPA fragment: return just the fragment
            // so spa.js can swap it into #app without a full page reload.
            return "pages/error :: error";
        }

        model.addAttribute("error", true);
        filler.defaultAttr(model, "");
        return "index";
    }
}
