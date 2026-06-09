package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@Controller
@RequestMapping("/experiments")
@RequiredArgsConstructor
public class ExperimentFeedbackController {

    private final ApiClient apiClient;

    @PostMapping("/feedback")
    public String submit(
            @RequestParam UUID experimentId,
            @RequestParam int npsScore,
            @RequestParam(required = false) String comment) {
        apiClient.post("/api/experiments/feedback",
                new FeedbackRequest(experimentId, npsScore, comment));
        return "fragments/experiment-feedback-widget :: confirmed";
    }

    record FeedbackRequest(UUID experimentId, int npsScore, String comment) {}
}
