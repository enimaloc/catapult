package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadValidator;
import fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/** The Twitchat card's event editor (twitchat-events.js): save, test, and one payload's card. */
@Controller
@RequiredArgsConstructor
public class TwitchatEventsController {

    private final ApiService apiService;
    private final TwitchatEventForms forms;
    private final TwitchatCatalog catalog;

    @PostMapping("/channel/{username}/twitchat/events/{eventType}")
    @ResponseBody
    public ResponseEntity<List<TwitchatValidationError>> save(@PathVariable String username, @PathVariable String eventType,
                                                              @RequestBody List<TwitchatEventActionDto> payloads) {
        return answer(apiService.saveTwitchatEvent(username, eventType, payloads));
    }

    @PostMapping("/channel/{username}/twitchat/events/{eventType}/test")
    @ResponseBody
    public ResponseEntity<List<TwitchatValidationError>> test(@PathVariable String username, @PathVariable String eventType,
                                                              @RequestBody List<TwitchatEventActionDto> payloads) {
        return answer(apiService.testTwitchatEvent(username, eventType, payloads));
    }

    /**
     * A payload's card: a new one (no dataJson) starts from its fields' defaults; back from raw
     * JSON to the form, it's refused (422 and why) when the JSON no longer fits the form.
     */
    @PostMapping("/channel/{username}/twitchat/payload-form")
    public String payloadForm(@PathVariable String username, @RequestParam String event,
                              @RequestBody TwitchatEventActionDto payload, Model model) {
        if (catalog.action(payload.action()).isEmpty()) {
            throw new TwitchatEventForms.UnknownAction(payload.action());
        }
        // A new payload (no dataJson) may leave required fields empty: it's a form to fill.
        TwitchatEventActionDto card = payload.dataJson() == null ? forms.newPayload(payload.action()) : payload;
        if (payload.dataJson() != null && "FORM".equals(card.mode())) {
            TwitchatPayloadValidator validator = new TwitchatPayloadValidator(catalog);
            List<TwitchatValidationError> errors = validator.validate(List.of(card));
            if (errors.isEmpty()) {
                // Keys the form has no field for would be silently dropped by going back to it.
                errors = validator.outsideForm(0, catalog.action(card.action()).orElseThrow().fields(),
                        validator.parse(card.dataJson()));
            }
            if (!errors.isEmpty()) {
                throw new FormRefused(errors);
            }
        }
        model.addAttribute("node", forms.payloadCard(event, card));
        return "pages/channel-tab/integrations :: node-alone";
    }

    @ExceptionHandler(FormRefused.class)
    @ResponseBody
    public ResponseEntity<List<TwitchatValidationError>> onFormRefused(FormRefused refused) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(refused.errors);
    }

    @ExceptionHandler(TwitchatEventForms.UnknownAction.class)
    @ResponseBody
    public ResponseEntity<List<TwitchatValidationError>> onUnknownAction(TwitchatEventForms.UnknownAction unknown) {
        return ResponseEntity.badRequest().body(List.of(new TwitchatValidationError(0, "action", unknown.getMessage())));
    }

    /** Raw JSON that no longer fits its action's form: why, for the payload's card to show. */
    static final class FormRefused extends RuntimeException {
        final List<TwitchatValidationError> errors;

        FormRefused(List<TwitchatValidationError> errors) {
            super(errors.size() + " field(s) don't fit the form");
            this.errors = errors;
        }
    }

    private static ResponseEntity<List<TwitchatValidationError>> answer(List<TwitchatValidationError> errors) {
        return errors.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().body(errors);
    }
}
