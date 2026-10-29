package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.Placement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.Slot;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes a configuration's extension layout to Twitch. The last configuration applied is remembered per user, so
 * the same one is not pushed twice in a row; the memory is lost on restart, which at worst re-applies a layout.
 * A Twitch error is logged in the activity log, never thrown: the extensions must not break a category change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtensionLayoutApplier {

    public static final String NO_GAME_KEY = CategoryChain.NO_GAME_FALLBACK_SOURCE;
    public static final String INCOMPLETE_KEY = CategoryChain.INCOMPLETE_FALLBACK_SOURCE;

    private final TwitchService twitchService;
    private final ActivityLogService activityLogService;

    private final Map<UUID, String> lastKeys = new ConcurrentHashMap<>();

    /** Identifies the configuration: persisted bindings by id, transient ones by their source name. */
    public static String keyOf(GameBinding binding) {
        return binding.getId() != null ? "binding:" + binding.getId() : binding.getSourceName();
    }

    /** Applies {@code layout} unless {@code key} is already the configuration in place; remembers {@code key}. */
    public void applyOnSwitch(UserAccount user, String key, Collection<ExtensionSlotSetting> layout) {
        String previous = lastKeys.put(user.getId(), key);
        if (key.equals(previous)) return;
        apply(user, layout);
    }

    /** Applies {@code layout} only when {@code key} is still the configuration in place (a settings edit). */
    public void applyIfCurrent(UserAccount user, String key, Collection<ExtensionSlotSetting> layout) {
        if (!key.equals(lastKeys.get(user.getId()))) return;
        apply(user, layout);
    }

    /** Forgets the configuration in place (end of stream). */
    public void forget(UserAccount user) {
        lastKeys.remove(user.getId());
    }

    private void apply(UserAccount user, Collection<ExtensionSlotSetting> layout) {
        if (layout == null || layout.isEmpty()) return;
        try {
            List<TwitchExtension> installed = twitchService.getUserExtensions(user);
            boolean needsActive = layout.stream()
                    .anyMatch(s -> s.getType() == TwitchExtensionsDto.Type.COMPONENT && s.getExtensionId() != null);
            TwitchExtensionPlacement active = needsActive ? twitchService.getActiveExtensions(user) : null;
            List<ExtensionSlotSetting> skipped = new ArrayList<>();
            TwitchExtensionPlacement placement = toPlacement(layout, installed, active, skipped);
            // An empty list while extensions are wanted means the read failed, not that each one is missing.
            boolean unreadable = installed.isEmpty() && !skipped.isEmpty();
            if (unreadable) {
                activityLogService.addEntry(user.getId(), "WARN", "Twitch extensions not applied: the installed"
                        + " extensions could not be read, the extension slots were left as they are");
            }
            for (ExtensionSlotSetting setting : unreadable ? List.<ExtensionSlotSetting>of() : skipped) {
                activityLogService.addEntry(user.getId(), "WARN", "Twitch extension " + setting.getExtensionName()
                        + " not applied to " + setting.getType() + " " + setting.getSlot()
                        + ": not installed or not activable");
            }
            if (placement.component() == null && placement.overlay() == null && placement.panel() == null) return;
            twitchService.setActiveExtensions(user, placement);
        } catch (RuntimeException e) {
            log.warn("Applying the extension layout for user {} failed: {}", user.getId(), e.toString());
            activityLogService.addEntry(user.getId(), "WARN", "Twitch extensions not applied: " + e.toString());
        }
    }

    /**
     * The partial placement for {@code layout}: a null extension empties its slot, the others take the installed
     * version. A setting whose extension is not installed or cannot be activated is added to {@code skipped}.
     *
     * @param active the current placement, read for the position of components; may be null
     */
    public static TwitchExtensionPlacement toPlacement(Collection<ExtensionSlotSetting> layout,
                                                       List<TwitchExtension> installed,
                                                       TwitchExtensionPlacement active,
                                                       List<ExtensionSlotSetting> skipped) {
        TwitchExtensionPlacement.Update update = TwitchExtensionPlacement.update();
        for (ExtensionSlotSetting setting : layout) {
            Slot slot;
            if (setting.getExtensionId() == null) {
                slot = Slot.empty();
            } else {
                TwitchExtension extension = installed.stream()
                        .filter(e -> setting.getExtensionId().equals(e.id()) && e.canActivate())
                        .findFirst().orElse(null);
                if (extension == null) {
                    skipped.add(setting);
                    continue;
                }
                slot = setting.getType() == TwitchExtensionsDto.Type.COMPONENT
                        ? componentSlot(extension, setting.getSlot(), active)
                        : Slot.of(extension.id(), extension.version());
            }
            switch (setting.getType()) {
                case COMPONENT -> update.component(setting.getSlot(), slot);
                case OVERLAY -> update.overlay(setting.getSlot(), slot);
                case PANEL -> update.panel(setting.getSlot(), slot);
                case MOBILE -> throw new IllegalArgumentException("Mobile extension slots cannot be configured");
            }
        }
        return update.build();
    }

    private static Slot componentSlot(TwitchExtension extension, int number, TwitchExtensionPlacement active) {
        Placement current = active == null ? null : active.component();
        Slot existing = current == null || number > current.slots().size() ? null : current.slots().get(number - 1);
        int x = existing != null && existing.x() != null ? existing.x() : 0;
        int y = existing != null && existing.y() != null ? existing.y() : 0;
        return Slot.at(extension.id(), extension.version(), x, y);
    }
}
