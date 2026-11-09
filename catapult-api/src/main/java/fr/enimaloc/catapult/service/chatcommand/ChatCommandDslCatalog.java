package fr.enimaloc.catapult.service.chatcommand;

import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.common.dto.chatcommand.CatalogDto;
import fr.enimaloc.catapult.common.dto.chatcommand.ServiceFunctionDto;
import fr.enimaloc.catapult.common.dto.chatcommand.TwOptionDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandSetting;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The block/dropdown catalog the command editor renders: the server stays the single source of truth for what a
 * streamer can build.
 */
@Service
@RequiredArgsConstructor
public class ChatCommandDslCatalog {

    private final ServiceFunctionRegistry serviceFunctionRegistry;
    private final ChatCommandSettingRepository settingRepository;
    private final TwDefinitionRepository twDefinitionRepository;

    /**
     * The settings block's dropdown needs the streamer's own saved keys (unlike context paths,
     * which are a fixed known catalog) — {@code user} is resolved best-effort by the caller so the rest of the
     * catalog (context paths, service functions) still renders even for a caller the JWT can't be matched to a
     * {@link UserAccount} for (e.g. an incomplete test JWT).
     */
    public CatalogDto catalog(Optional<UserAccount> user) {
        List<ServiceFunctionDto> functions = serviceFunctionRegistry.all().stream()
            .map(f -> new ServiceFunctionDto(f.namespace(), f.name(), f.parameterNames(), f.returnKeys(),
                f.optionalParameterNames(), f.isAction()))
            .toList();
        List<String> settingKeys = user
            .map(u -> settingRepository.findByUser(u).stream()
                .map(ChatCommandSetting::getKey).sorted().toList())
            .orElse(List.of());
        List<TwOptionDto> knownTws = twDefinitionRepository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(d -> new TwOptionDto(d.getId(), d.getLabel()))
            .toList();
        return new CatalogDto(List.copyOf(PlaceholderResolver.KNOWN_PATHS), functions, settingKeys, knownTws);
    }
}
