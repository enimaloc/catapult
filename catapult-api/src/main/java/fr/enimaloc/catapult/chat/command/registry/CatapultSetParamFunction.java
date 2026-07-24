package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one write action, scoped to the streamer's own {@link ChatCommandSetting} store (the same
 * one the dedicated "Paramètres" UI section manages) — never {@code UserFlag}, which is an
 * admin/experiment-targeting mechanism a command must not be able to influence.
 */
@Component
@RequiredArgsConstructor
public class CatapultSetParamFunction implements ServiceFunction {

    // Mirrors ApiChatCommandSettingsController's VALID_KEY/RESERVED_KEYS — an invalid key must
    // fail the same way there (JsCompiler's prototype-pollution guard rejects these as
    // ctx.settings.<key> segments too), not silently no-op.
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> RESERVED_KEYS = Set.of("__proto__", "constructor", "prototype");

    private final ChatCommandSettingRepository settingRepository;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "setParam";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("key", "value");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String key = String.valueOf(args[0]);
        String value = String.valueOf(args[1]);
        if (!VALID_KEY.matcher(key).matches() || RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Invalid catapult#setParam key: " + key);
        }
        ChatCommandSetting setting = settingRepository.findByUserAndKey(user, key).orElseGet(() -> {
            ChatCommandSetting s = new ChatCommandSetting();
            s.setUser(user);
            s.setKey(key);
            return s;
        });
        setting.setValue(value);
        settingRepository.save(setting);
        return "";
    }
}
