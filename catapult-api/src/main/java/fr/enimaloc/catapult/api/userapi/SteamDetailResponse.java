package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import fr.enimaloc.catapult.service.SteamStoreService;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public record SteamDetailResponse(String storeUrl, Set<String> ccls, ParentApp parentApp, Page page) {
    public record ParentApp(@JsonIgnore String baseUrl, @JsonUnwrapped SteamStoreService.ResolvedParentApp parentApp) {
        @JsonGetter
        public String more() {
            return baseUrl + ApiV2.PATH + "/steam/" + parentApp.appId();
        }
    }

    public record Page(@JsonIgnore String baseUrl, @JsonIgnore Locale locale,
                      @JsonUnwrapped @JsonIgnoreProperties({"fullgame", "dlc", "releaseDate"}) SteamStoreService.SteamStorePage page) {
        // Steam's own release_date.date is a raw, locale-formatted display string (e.g.
        // "10 Oct, 2007") — replace it with a parsed Instant, same as GameInfoResponse.SteamObject.
        public record ReleaseDate(Instant releaseDate, boolean comingSoon) {}

        @JsonGetter("releaseDate")
        public ReleaseDate releaseDate0() {
            SteamStoreService.SteamStorePage.ReleaseDate rd = page().releaseDate();
            if (rd == null) return null;
            return new ReleaseDate(SteamReleaseDate.parse(rd, locale), rd.comingSoon());
        }

        @JsonGetter("demo")
        public Demo[] demo0() {
            return page().demos() == null ? null : Arrays.stream(page.demos())
                    .map(d -> new Demo(baseUrl, d)).toArray(Demo[]::new);
        }

        @JsonGetter("fullgame")
        public FullGame fullGame0() {
            return page().fullgame() == null ? null : new FullGame(baseUrl, page().fullgame());
        }

        @JsonGetter("dlc")
        public Dlc[] dlc0() {
            return page.dlc() == null ? null : Arrays.stream(page().dlc())
                    .mapToObj(d -> new Dlc(baseUrl, d)).toArray(Dlc[]::new);
        }

        public record Demo(@JsonIgnore String baseUrl, @JsonUnwrapped SteamStoreService.SteamStorePage.Demo demo) {
            @JsonGetter
            public String more() {
                return baseUrl + ApiV2.PATH + "/steam/" + demo.appid();
            }
        }

        public record FullGame(@JsonIgnore String baseUrl, @JsonUnwrapped SteamStoreService.SteamStorePage.FullGame fullGame) {
            @JsonGetter
            public String more() {
                return baseUrl + ApiV2.PATH + "/steam/" + fullGame.appid();
            }
        }

        public record Dlc(int appId, String more) {
            public Dlc(String baseUrl, int appId) {
                this(appId, baseUrl + ApiV2.PATH + "/steam/" + appId);
            }
        }
    }
}
