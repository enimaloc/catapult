package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TwLabelService {
    private final MessageSource messageSource;
    private final TwDefinitionRepository definitionRepo;

    /** Resolves a localized label for a TW slug. Falls back to DB label, then to the slug. */
    public String resolve(String twId, Locale locale) {
        try {
            return messageSource.getMessage("tw." + twId + ".label", null, locale);
        } catch (NoSuchMessageException ignored) {
            return definitionRepo.findById(twId).map(d -> d.getLabel()).orElse(twId);
        }
    }
}
