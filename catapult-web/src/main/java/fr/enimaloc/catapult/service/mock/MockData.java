package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.ChannelDto;
import fr.enimaloc.catapult.common.dto.ChannelListResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class MockData {
    private MockData() {}

    public static final ChannelDto ONLINE_USER = new ChannelDto(
            UUID.nameUUIDFromBytes("online".getBytes(StandardCharsets.UTF_8)),
            "00000001",
            "enimaloc_stream",
            "https://static-cdn.jtvnw.net/jtv_user_pictures/a69d9843-4cd4-4b3a-815d-0101b31e4790-profile_image-70x70.png",
            true
    );
    public static final ChannelDto OFFLINE_USER = new ChannelDto(
            UUID.nameUUIDFromBytes("offline".getBytes(StandardCharsets.UTF_8)),
            "00000002",
            "enimaloc",
            "https://static-cdn.jtvnw.net/jtv_user_pictures/a69d9843-4cd4-4b3a-815d-0101b31e4790-profile_image-70x70.png",
            false
    );

    public static ChannelListResponse getChannelList(String jwt) {
        return switch (jwt) {
            case "0" -> new ChannelListResponse("00000000", List.of(ONLINE_USER, OFFLINE_USER));
            case "1" -> new ChannelListResponse("00000000", List.of(ONLINE_USER));
            default -> new ChannelListResponse("00000000", List.of(OFFLINE_USER));
        };
    }

    public static final fr.enimaloc.catapult.common.dto.ChannelUserDto CHANNEL_USER =
            new fr.enimaloc.catapult.common.dto.ChannelUserDto(
                    ONLINE_USER.twitchId(), ONLINE_USER.twitchId(),
                    ONLINE_USER.twitchUsername(), ONLINE_USER.profileImageUrl());

    public static fr.enimaloc.catapult.common.dto.ChannelPageData getChannelPage(String username, String status, String source) {
        var sampleBinding = new fr.enimaloc.catapult.common.dto.BindingDto(
                "sample-binding-1", "AUTO", "STEAM", "Celeste",
                "509658", "Celeste", false, true, java.util.Set.of("violent-graphic"),
                false, false, java.util.Set.of());
        return new fr.enimaloc.catapult.common.dto.ChannelPageData(
                CHANNEL_USER, username, true, true, true,
                new fr.enimaloc.catapult.common.dto.GameDto("Celeste", "STEAM"),
                new fr.enimaloc.catapult.common.dto.PagedBindings(0, 1, 1, List.of(sampleBinding)),
                List.of(), java.util.Set.of(), List.of(), java.util.Set.of(),
                status, source, true, true, true, false, false, false, false, 15L,
                false, false, "00000000-0000-0000-0000-000000000000");
    }

    public static fr.enimaloc.catapult.common.dto.UserSettingsDto getChannelSettings() {
        return new fr.enimaloc.catapult.common.dto.UserSettingsDto(
                true, java.util.Set.of("violent-graphic"),
                null, null, java.util.Set.of(), false, false, false,
                null, null, java.util.Set.of(),
                List.of(new fr.enimaloc.catapult.common.dto.CclDto("violent-graphic", "Violence graphique")),
                false, java.util.Set.of(),
                List.of(new fr.enimaloc.catapult.common.dto.TwDto("jumpscares", "Jumpscares")));
    }
}
