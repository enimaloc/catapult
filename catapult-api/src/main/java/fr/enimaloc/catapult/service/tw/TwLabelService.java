package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TwLabelService {
    private final MessageSource messageSource;
    private final TwDefinitionRepository definitionRepo;

    /** Resolves a localized label for a TW slug. Falls back to DB label, then to the slug. */
    public String resolve(String twId, Locale locale) {
        String code = "tw." + twId + ".label";
        // A miss is a null (standard MessageSource) or the code echoed back (IcuMessageSource).
        String message = messageSource.getMessage(code, null, null, locale);
        if (message != null && !message.equals(code)) {
            return message;
        }
        return definitionRepo.findById(twId).map(TwDefinition::getLabel).orElse(twId);
    }
}
