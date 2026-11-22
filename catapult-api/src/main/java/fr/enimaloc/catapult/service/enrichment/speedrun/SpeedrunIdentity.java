package fr.enimaloc.catapult.service.enrichment.speedrun;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The speedrun.com account used for a member, and how it was found. */
public record SpeedrunIdentity(String userId, String name, String url, Source source) {

    /** How the account was found, in the order they are tried. */
    public enum Source {
        /** The member's personal API key: speedrun.com said whose it is. */
        @JsonProperty("api_key") API_KEY("api_key"),
        /** The only speedrun.com profile linking the member's Twitch login. */
        @JsonProperty("twitch") TWITCH("twitch"),
        /** The name the member declared. */
        @JsonProperty("declared") DECLARED("declared");

        private final String key;

        Source(String key) {
            this.key = key;
        }

        /** The name it is serialized under. */
        public String key() {
            return key;
        }
    }
}
