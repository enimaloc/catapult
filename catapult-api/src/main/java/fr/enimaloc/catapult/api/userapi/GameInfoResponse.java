package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.service.SteamStoreService;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record GameInfoResponse(@JsonView(Summary.class) boolean inGame, IgdbObject igdb, SteamObject steam, XboxObject xbox, DtddObject dtdd,
                               CatapultObject catapult) {

    public GameInfoResponse(boolean inGame) {
        this(inGame, null, null, null, null, null);
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
                .findFirst().orElse(null);
    }

    @JsonView(Summary.class)
    public String name() {
        return streamObjectsOf(HasName.class).map(HasName::name)
                .findFirst().orElse(null);
    }

    @JsonView(Summary.class)
    public String tws() {
        return streamObjectsOf(HasTW.class).map(HasTW::tws)
                .flatMap(Collection::stream).collect(Collectors.joining(", "));
    }

    @JsonView(Summary.class)
    public Object releaseDate() {
        record ReleaseDate(@JsonView(Summary.class) Instant releaseDate, @JsonView(Summary.class) boolean comingSoon) {}
        return streamObjectsOf(HasReleaseDate.class).map(rd -> new ReleaseDate(rd.releaseDate(), rd.comingSoon()))
                .findFirst().orElse(null);
    }

    @JsonView(Summary.class)
    public IaDisclosure ia() {
        return streamObjectsOf(HasIaDisclosure.class).map(HasIaDisclosure::ia)
                .findFirst().orElse(null);
    }

    public record IgdbObject(String igdbId, String slug, String summary, String coverUrl,
                             List<String> genres, String more)
            implements HasDescription, HasMore {
        @JsonGetter("url")
        public String url() {
            return "https://www.igdb.com/game/" + slug;
        }

        @Override
        public String description() {
            return summary;
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
            implements HasMore, HasTW {}

    public record CatapultObject(UUID bindingId, Instant createdAt, Set<String> tws, Set<String> ccls,
                                 String more)
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
        Set<String> tws();
    }

    public interface HasReleaseDate {
        boolean comingSoon();
        Instant releaseDate();
    }

    public interface HasIaDisclosure {
        IaDisclosure ia();
    }
}
