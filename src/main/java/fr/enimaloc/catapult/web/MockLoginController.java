package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@Profile("mock-web")
@RequestMapping("/mock-login")
@RequiredArgsConstructor
public class MockLoginController {

    private final UserAccountRepository userAccountRepository;

    @Value("${app.owner-id:}")
    private String ownerId;

    @GetMapping
    public String loginPage(Model model) {
        model.addAttribute("users", userAccountRepository.findAll());
        model.addAttribute("ownerId", ownerId);
        return "mock-login";
    }

    @PostMapping
    public String login(@RequestParam UUID userId, HttpServletRequest request) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        boolean isAdmin = ownerId.equals(user.getTwitchId());
        CatapultOAuth2User principal = new CatapultOAuth2User(null, user, isAdmin);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        SecurityContextHolder.getContext().setAuthentication(auth);
        HttpSession session = request.getSession(true);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());

        return "redirect:/app";
    }
}
