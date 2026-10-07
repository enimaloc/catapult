package fr.enimaloc.catapult.service.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Deserialization target for the mock login form's JSON payload ({@code jwt-select.html}
 * serializes every field into the login code). Every field has a default, so a partial payload
 * still yields a complete session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MockConfig {
    public String username = "enimaloc_stream";
    public String twitchId = "00000001";
    public String avatarUrl = MockPresets.AVATAR_URL;
    public boolean live = true;
    public boolean botEnabled = true;
    public Steam steam = new Steam();
    public Xbox xbox = new Xbox();
    public Minecraft minecraft = new Minecraft();
    public Binding binding = new Binding();
    public Settings settings = new Settings();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Steam {
        public boolean connected = true;
        public boolean hasPersonalToken = false;
        public boolean tokenShared = false;
        public boolean profilePrivate = false;
        public boolean rateLimited = false;
        public boolean offlineMode = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Xbox {
        public boolean connected = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Minecraft {
        public String status = "NONE";
        public String minecraftName = null;
        public String serviceAccountUsername = null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Binding {
        public String sourceType = "STEAM";
        public String sourceName = "STUNTBOOST";
        public String twitchGameId = "000001";
        public String twitchGameName = "Stuntboost";
        public String status = "AUTO";
        public boolean ignored = false;
        public boolean cclEnabled = true;
        public boolean twEnabled = true;
        public boolean twOverride = false;
        public List<String> ccls = List.of();
        public List<String> tws = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        public boolean cclFeatureEnabled = true;
        public boolean twFeatureEnabled = false;
        public List<String> blockedCcls = List.of();
        public List<String> blockedTws = List.of();
    }
}
