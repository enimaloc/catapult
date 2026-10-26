package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.link.ExternalLinks;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.service.steam.SteamStoreService;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record GameInfoResponse(@JsonView(Summary.class) boolean inGame, IgdbObject igdb, SteamObject steam, XboxObject xbox, DtddObject dtdd,
                               CatapultObject catapult, @JsonIgnore String detectedName) {

    public GameInfoResponse(boolean inGame) {
        this(inGame, null, null, null, null, null, null);
    }

    private static final Map<Class<?>, Integer> ORDER = Map.of(
            IgdbObject.class, 2,
            SteamObject.class, 0,
            XboxObject.class, 1,
            DtddObject.class, 3,
            CatapultObject.class, 4
    );

    @JsonIgnore
    public Stream<? extends Record> streamObjects() {
        return Stream.of(igdb, steam, xbox, dtdd, catapult)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(
                        object -> ORDER.getOrDefault(object.getClass(), Integer.MAX_VALUE)
                ));
    }

    @JsonIgnore
    public <T> Stream<T> streamObjectsOf(Class<T> clazz) {
        return streamObjects().filter(clazz::isInstance).map(clazz::cast);
    }

    @JsonView(Summary.class)
    public String description() {
        return streamObjectsOf(HasDescription.class).map(HasDescription::description)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    @JsonView(Summary.class)
    public String name() {
        return streamObjectsOf(HasName.class).map(HasName::name)
                .filter(Objects::nonNull).findFirst().orElse(detectedName);
    }

    @JsonView(Summary.class)
    public String tws() {
        return streamObjectsOf(HasTW.class).map(HasTW::twLabels)
                .flatMap(Collection::stream).filter(Objects::nonNull).distinct().sorted()
                .collect(Collectors.joining(", "));
    }

    @JsonView(Summary.class)
    public Object releaseDate() {
        record ReleaseDate(@JsonView(Summary.class) Instant releaseDate, @JsonView(Summary.class) boolean comingSoon) {}
        return streamObjectsOf(HasReleaseDate.class)
                .filter(rd -> rd.releaseDate() != null || rd.comingSoon())
                .map(rd -> new ReleaseDate(rd.releaseDate(), rd.comingSoon()))
                .findFirst().orElse(null);
    }

    /** IGDB's page of the game, null when IGDB doesn't know it. */
    @JsonView(Summary.class)
    public String igdbUrl() {
        return igdb == null ? null : igdb.url();
    }

    /** The store page of the game played: Steam's, else the Xbox Store's; null for any other. */
    @JsonView(Summary.class)
    public String storeUrl() {
        if (steam != null && steam.appId() != null) return ExternalLinks.steamApp(steam.appId());
        return xbox == null ? null : xbox.storeUrl();
    }

    @JsonView(Summary.class)
    public IaDisclosure ia() {
        return streamObjectsOf(HasIaDisclosure.class).map(HasIaDisclosure::ia)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    public record IgdbObject(String igdbId, String slug, String summary, String coverUrl,
                             List<String> genres, String more, @JsonIgnore Instant firstReleaseDate)
            implements HasDescription, HasMore, HasReleaseDate {
        @JsonGetter("url")
        public String url() {
            return ExternalLinks.igdbGame(slug);
        }

        @Override
        public String description() {
            return summary;
        }

        @Override
        public Instant releaseDate() {
            return firstReleaseDate;
        }

        @Override
        public boolean comingSoon() {
            return firstReleaseDate != null && firstReleaseDate.isAfter(Instant.now());
        }
    }

    public record SteamObject(
            String appId, String name, String description, SteamStoreService.SteamStorePage.Category[] categories,
            String[] developers, String legalNotice, String headerImage,
            SteamStoreService.SteamStorePage.ReleaseDate steamReleaseDate,
            SteamStoreService.SteamStorePage.ContentDescriptors contentDescriptors,
            IaDisclosure ia, @JsonIgnore Locale locale, String more)
            implements HasName, HasDescription, HasReleaseDate, HasMore, HasIaDisclosure {
        @Override
        public Instant releaseDate() {
            return SteamReleaseDate.parse(steamReleaseDate, locale);
        }

        @Override
        public boolean comingSoon() {
            return steamReleaseDate != null && steamReleaseDate.comingSoon();
        }
    }

    public record XboxObject(String productId, String name, String description, String publisherName,
                             String developerName, String coverImage, String storeUrl, String more)
            implements HasName, HasDescription, HasMore {}

    public record DtddObject(long dtddId, String url, Set<String> twsIds, Set<String> tws, String more)
            implements HasMore, HasTW {
        @Override
        public Set<String> twLabels() {
            return tws;
        }
    }

    public record CatapultObject(UUID bindingId, Instant createdAt, Set<String> tws, Set<String> ccls,
                                 String more, @JsonIgnore Set<String> twLabels)
            implements HasMore, HasTW {}

    public record IaDisclosure(@JsonView(Summary.class) boolean hasDisclosure, @JsonView(Summary.class) String note) {}

    public interface Summary {}

    public interface HasName {
        String name();
    }

    public interface HasDescription {
        String description();
    }

    public interface HasMore {
        String more();
    }

    public interface HasTW {
        Set<String> twLabels();
    }

    public interface HasReleaseDate {
        boolean comingSoon();
        Instant releaseDate();
    }

    public interface HasIaDisclosure {
        IaDisclosure ia();
    }
}
