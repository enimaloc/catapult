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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes a configuration's extension layout to Twitch. The last configuration applied is remembered per user, so
 * the same one is not pushed twice in a row.
 * <p>
 * A configuration's extensions only last while it is in force: before a slot is changed for the first time, what it
 * held is kept, and it is put back as soon as the configuration in force no longer sets that slot (another game that
 * keeps it, leaving the game, the end of the stream). Everything is kept in memory: a restart forgets the
 * configuration in place, which at worst re-applies a layout, and what the slots held, which are then left as the
 * last configuration put them.
 * <p>
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
    /** Per user, what each slot changed by a configuration held before; an inactive slot is {@link Slot#empty()}. */
    private final Map<UUID, Map<SlotRef, Slot>> originals = new ConcurrentHashMap<>();

    /** One Twitch slot, numbered from 1. */
    record SlotRef(TwitchExtensionsDto.Type type, int number) {
        static SlotRef of(ExtensionSlotSetting setting) {
            return new SlotRef(setting.getType(), setting.getSlot());
        }
    }

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

    /** No configuration in force any more (game left, end of stream): every changed slot gets back what it held. */
    public void restore(UserAccount user) {
        lastKeys.remove(user.getId());
        apply(user, List.of());
    }

    private void apply(UserAccount user, Collection<ExtensionSlotSetting> layout) {
        Map<SlotRef, Slot> held = originals.computeIfAbsent(user.getId(), id -> new ConcurrentHashMap<>());
        Set<SlotRef> set = new HashSet<>();
        layout.forEach(setting -> set.add(SlotRef.of(setting)));
        Map<SlotRef, Slot> toRestore = new HashMap<>(held);
        toRestore.keySet().removeAll(set);
        if (layout.isEmpty() && toRestore.isEmpty()) return;
        try {
            List<TwitchExtension> installed = twitchService.getUserExtensions(user);
            TwitchExtensionPlacement active = layout.isEmpty() ? null : twitchService.getActiveExtensions(user);
            if (readable(active)) {
                set.forEach(ref -> held.computeIfAbsent(ref, r -> current(active, r)));
            } else if (active != null) {
                activityLogService.addEntry(user.getId(), "WARN", "Twitch extensions in place could not be read:"
                        + " the extensions changed now will not be put back when this configuration ends");
            }
            TwitchExtensionPlacement.Update update = TwitchExtensionPlacement.update();
            Set<SlotRef> restored = restore(user, toRestore, installed, update);
            List<ExtensionSlotSetting> skipped = new ArrayList<>();
            // The layout goes on top of the slots as they are once put back: a component takes its original position
            fill(update, layout, installed, readable(active) ? restoredComponents(active, held) : null, skipped);
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
            TwitchExtensionPlacement placement = update.build();
            if (placement.component() != null || placement.overlay() != null || placement.panel() != null) {
                twitchService.setActiveExtensions(user, placement);
            }
            restored.forEach(held::remove);
        } catch (RuntimeException e) {
            log.warn("Applying the extension layout for user {} failed: {}", user.getId(), e.toString());
            activityLogService.addEntry(user.getId(), "WARN", "Twitch extensions not applied: " + e.toString());
        }
    }

    /**
     * Adds to {@code update} what each slot of {@code toRestore} held; returns the slots done with. An extension
     * uninstalled since is given up and logged; while the installed list cannot be read, the slot is tried later.
     */
    private Set<SlotRef> restore(UserAccount user, Map<SlotRef, Slot> toRestore, List<TwitchExtension> installed,
                                 TwitchExtensionPlacement.Update update) {
        Set<SlotRef> done = new HashSet<>();
        toRestore.forEach((ref, slot) -> {
            Slot back = slot;
            if (slot.active()) {
                TwitchExtension extension = installed.stream()
                        .filter(e -> e.id().equals(slot.id()) && e.canActivate())
                        .findFirst().orElse(null);
                if (extension == null) {
                    if (installed.isEmpty()) return;
                    activityLogService.addEntry(user.getId(), "WARN", "Twitch extension " + slot.id()
                            + " not put back in " + ref.type() + " " + ref.number()
                            + ": no longer installed or not activable");
                    done.add(ref);
                    return;
                }
                // The version installed now: the one read before may have been updated since
                back = slot.x() != null
                        ? Slot.at(extension.id(), extension.version(), slot.x(), slot.y())
                        : Slot.of(extension.id(), extension.version());
            }
            put(update, ref.type(), ref.number(), back);
            done.add(ref);
        });
        return done;
    }

    /** The slot as {@code active} reads it, cleaned to what a PUT takes back. */
    private static Slot current(TwitchExtensionPlacement active, SlotRef ref) {
        Placement placement = switch (ref.type()) {
            case COMPONENT -> active.component();
            case OVERLAY -> active.overlay();
            case PANEL -> active.panel();
            case MOBILE -> null;
        };
        Slot slot = placement == null || ref.number() > placement.slots().size()
                ? null : placement.slots().get(ref.number() - 1);
        if (slot == null || !slot.active() || slot.id() == null) return Slot.empty();
        return ref.type() == TwitchExtensionsDto.Type.COMPONENT
                ? Slot.at(slot.id(), slot.version(), slot.x() != null ? slot.x() : 0, slot.y() != null ? slot.y() : 0)
                : Slot.of(slot.id(), slot.version());
    }

    /** {@code active} with each component slot changed by a configuration back to what it held before. */
    private static TwitchExtensionPlacement restoredComponents(TwitchExtensionPlacement active,
                                                                Map<SlotRef, Slot> held) {
        List<Slot> slots = active.component() == null
                ? Arrays.asList(null, null) : new ArrayList<>(active.component().slots());
        for (int number = 1; number <= slots.size(); number++) {
            Slot original = held.get(new SlotRef(TwitchExtensionsDto.Type.COMPONENT, number));
            if (original != null) slots.set(number - 1, original);
        }
        return new TwitchExtensionPlacement(new TwitchExtensionPlacement.ComponentPlacement(slots.get(0), slots.get(1)),
                active.overlay(), active.panel());
    }

    /** False for {@link TwitchExtensionPlacement#unreadable()}, what a failed read answers. */
    private static boolean readable(TwitchExtensionPlacement active) {
        return active != null
                && (active.component() != null || active.overlay() != null || active.panel() != null);
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
        fill(update, layout, installed, active, skipped);
        return update.build();
    }

    private static void fill(TwitchExtensionPlacement.Update update, Collection<ExtensionSlotSetting> layout,
                             List<TwitchExtension> installed, TwitchExtensionPlacement active,
                             List<ExtensionSlotSetting> skipped) {
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
            put(update, setting.getType(), setting.getSlot(), slot);
        }
    }

    private static void put(TwitchExtensionPlacement.Update update, TwitchExtensionsDto.Type type, int number,
                            Slot slot) {
        switch (type) {
            case COMPONENT -> update.component(number, slot);
            case OVERLAY -> update.overlay(number, slot);
            case PANEL -> update.panel(number, slot);
            case MOBILE -> throw new IllegalArgumentException("Mobile extension slots cannot be configured");
        }
    }

    private static Slot componentSlot(TwitchExtension extension, int number, TwitchExtensionPlacement active) {
        Placement current = active == null ? null : active.component();
        Slot existing = current == null || number > current.slots().size() ? null : current.slots().get(number - 1);
        int x = existing != null && existing.x() != null ? existing.x() : 0;
        int y = existing != null && existing.y() != null ? existing.y() : 0;
        return Slot.at(extension.id(), extension.version(), x, y);
    }
}
