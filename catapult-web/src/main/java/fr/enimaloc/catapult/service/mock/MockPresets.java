package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.CclDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.TwDto;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import org.hibernate.validator.constraints.Range;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The fixed data mock sessions are built from, and the value lists the mock forms offer. */
public final class MockPresets {

    public static final String DEFAULT_AVATAR_FMT =
            "https://static-cdn.jtvnw.net/user-default-pictures-uv/%s-profile_image-%s.png";

    public static final String[] DEFAULT_AVATAR_ID = new String[] {
            "de130ab0-def7-11e9-b668-784f43822e80",
            "dbdc9198-def8-11e9-8681-784f43822e80",
            "41780b5a-def8-11e9-94d9-784f43822e80",
            "998f01ae-def8-11e9-b95c-784f43822e80",
            "215b7342-def9-11e9-9a66-784f43822e80",
            "ce57700a-def9-11e9-842d-784f43822e80",
            "13e5fa74-defa-11e9-809c-784f43822e80",
            "294c98b5-e34d-42cd-a8f0-140b72fba9b0",
            "75305d54-c7cc-40d1-bb9c-91fbe85943c7",
            "ebb84563-db81-4b9c-8940-64ed33ccfc7b",
            "ead5c8b2-a4c9-4724-b1dd-9f00b46cbd3d"
    };

    public static String getDefaultAvatar(int size) {
        return getDefaultAvatar(new Random(), size);
    }

    public static String getDefaultAvatar(Random random) {
        return getDefaultAvatar(random, 70);
    }

    public static String getDefaultAvatar() {
        return getDefaultAvatar(new Random(), 70);
    }

    public static String getDefaultAvatar(Random random, int size) {
        String sizeStr = switch (size) {
            case 28, 50, 70, 150, 300, 600 -> size + "x" + size;
            default -> throw new IllegalStateException("Unexpected value: " + size);
        };
        String id = DEFAULT_AVATAR_ID[random.nextInt(DEFAULT_AVATAR_ID.length)];
        return DEFAULT_AVATAR_FMT.formatted(id, sizeStr);
    }

    /** Fixed catalog the mock login form picks blocked entries from — not itself customizable. */
    public static final List<CclDto> AVAILABLE_CCLS = List.of(
            new CclDto("DrugsIntoxication", "Drogue"),
            new CclDto("ProfanityVulgarity", "Vulgarité"),
            new CclDto("SexualThemes", "Thèmes sexuels"),
            new CclDto("ViolentGraphic", "Violence graphique")
    );

    public static final List<TwDto> AVAILABLE_TWS = List.of(
            new TwDto("mature_content", "Mature Content"),
            new TwDto("death_animal", "Animal death"),
            new TwDto("supernatural", "Supernatural"),
            new TwDto("sensory_motion", "Motion sickness"),
            new TwDto("graphic_violence", "Graphic violence"),
            new TwDto("phobia_snakes", "Snake phobia"),
            new TwDto("violence", "Violence"),
            new TwDto("phobia_water", "Water phobia"),
            new TwDto("animal_abuse", "Animal abuse"),
            new TwDto("gross_out", "Gross out"),
            new TwDto("phobia_insects", "Insect phobia"),
            new TwDto("death_human", "Human death"),
            new TwDto("asphyxiation", "Asphyxiation"),
            new TwDto("social_issue", "Social Issue")
    );

    public static final List<String> BINDING_STATUSES = List.of("AUTO", "MANUAL", "INCOMPLETE");
    public static final List<String> SOURCE_TYPES = List.of("STEAM", "XBOX", "MINECRAFT");
    public static final List<String> MINECRAFT_STATUSES =
            List.of("NONE", "PENDING", "INVITE_REJECTED", "ACCEPTED", "REMOVED", "FULL");

    /** The quick-launch channels: "0" is live, "1" offline. */
    public static final List<ChannelDto> CHANNELS = List.of(
            new ChannelDto(uuidOf("online"), "00000001", "enimaloc_stream", getDefaultAvatar(), true),
            new ChannelDto(uuidOf("offline"), "00000002", "enimaloc", getDefaultAvatar(), false)
    );

    public static final List<BindingDto> BINDINGS = List.of(
            binding("STUNTBOOST", "000001", "Stuntboost", Set.of(), Set.of()),
            binding("CONTROL Resonant", "1338428218", "Control Resonant",
                    Set.of("ProfanityVulgarity", "ViolentGraphic", "SexualThemes"),
                    Set.of("mature_content", "graphic_violence")),
            binding("Valheim", "508455", "Valheim",
                    Set.of("DrugsIntoxication", "ProfanityVulgarity", "ViolentGraphic"),
                    Set.of("death_animal", "supernatural", "sensory_motion", "graphic_violence", "phobia_snakes",
                            "violence", "phobia_water", "animal_abuse", "gross_out", "phobia_insects",
                            "death_human", "asphyxiation", "social_issue")),
            binding("Jusant", "524807049", "Jusant", Set.of(), Set.of()),
            binding("MainFrames", "656286460", "MainFrames", Set.of("DrugsIntoxication"), Set.of())
    );

    /** A Twitch category the mock game search can return. */
    public record TwitchCategory(String id, String name) {}

    /** One category per preset binding, under its Twitch name. */
    public static final List<TwitchCategory> CATEGORIES = BINDINGS.stream()
            .map(binding -> new TwitchCategory(binding.twitchGameId(), binding.twitchGameName()))
            .toList();

    public static final UserSettingsDto DEFAULT_SETTINGS = new UserSettingsDto(
            true, Set.of("violent-graphic"),
            null, null, Set.of(), false, false, false,
            null, null, Set.of(),
            AVAILABLE_CCLS, false, Set.of(), AVAILABLE_TWS);

    private MockPresets() {
    }

    /** Stable id derived from a name, so the same preset always gets the same id. */
    static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static BindingDto binding(String sourceName, String twitchGameId, String twitchGameName,
                                      Set<String> ccls, Set<String> tws) {
        return new BindingDto(uuidOf(sourceName).toString(), "AUTO", "STEAM", sourceName, twitchGameId,
                twitchGameName, false, true, ccls, true, false, tws);
    }
}
