package fr.enimaloc.catapult.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private static final ParameterizedTypeReference<List<FeedbackDto>> SUBMISSIONS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ApiClient apiClient;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("submissions", apiClient.get("/api/feedback", SUBMISSIONS_TYPE));
        return "feedback";
    }

    @PostMapping
    public String submit(
            @RequestParam String type,
            @RequestParam String title,
            @RequestParam(required = false) String description) {
        apiClient.post("/api/feedback", new SubmitRequest(type, title, description), Object.class);
        return "fragments/feedback-widget :: submitted";
    }

    @PostMapping("/{id}/unsubscribe")
    public String unsubscribe(@PathVariable UUID id) {
        apiClient.post("/api/feedback/{id}/unsubscribe", null, id);
        return "redirect:/feedback";
    }

    record SubmitRequest(String type, String title, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FeedbackDto(
            String id,
            String type,
            String title,
            String description,
            boolean subscribed,
            String gitlabIssueUrl,
            Integer gitlabIssueIid
    ) {}
}
