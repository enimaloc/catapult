package fr.enimaloc.catapult.domain.binding;

import fr.enimaloc.catapult.common.dto.channel.ExtensionSlotDto;
import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** The extension a configuration puts in one Twitch slot; a null {@code extensionId} empties the slot. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ExtensionSlotSetting {

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", nullable = false)
    private TwitchExtensionsDto.Type type;

    @Column(name = "slot_number", nullable = false)
    private int slot;

    @Column(name = "extension_id")
    private String extensionId;

    @Column(name = "extension_name")
    private String extensionName;

    public static ExtensionSlotSetting fromDto(ExtensionSlotDto dto) {
        return new ExtensionSlotSetting(dto.type(), dto.slot(), dto.extensionId(), dto.extensionName());
    }

    public ExtensionSlotDto toDto() {
        return new ExtensionSlotDto(type, slot, extensionId, extensionName);
    }

    /** @throws IllegalArgumentException on a mobile slot, a slot out of Twitch's bounds or a duplicated slot */
    public static Set<ExtensionSlotSetting> validated(Collection<ExtensionSlotDto> dtos) {
        Set<ExtensionSlotSetting> result = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (ExtensionSlotDto dto : dtos) {
            if (dto.type() == null) {
                throw new IllegalArgumentException("Extension slot type is required");
            }
            int max = switch (dto.type()) {
                case COMPONENT -> 2;
                case OVERLAY -> 1;
                case PANEL -> 3;
                case MOBILE -> throw new IllegalArgumentException("Mobile extension slots cannot be configured");
            };
            if (dto.slot() < 1 || dto.slot() > max) {
                throw new IllegalArgumentException("Slot " + dto.slot() + " is out of bounds for " + dto.type());
            }
            if (!seen.add(dto.type() + ":" + dto.slot())) {
                throw new IllegalArgumentException("Duplicate slot " + dto.type() + " " + dto.slot());
            }
            result.add(fromDto(dto));
        }
        return result;
    }
}
