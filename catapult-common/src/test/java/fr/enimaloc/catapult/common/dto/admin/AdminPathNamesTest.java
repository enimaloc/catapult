package fr.enimaloc.catapult.common.dto.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin enums named by a catapult-web path segment and routed to a catapult-api path. */
class AdminPathNamesTest {

    @Test
    void apiKeyProviders_routeToTheirKeysEndpoint() {
        assertThat(ApiKeyProvider.STEAM.apiPath()).isEqualTo("steam-keys");
        assertThat(ApiKeyProvider.DTDD.apiPath()).isEqualTo("dtdd-keys");
        assertThat(ApiKeyProvider.STEAM.pathName()).isEqualTo("steam");
        assertThat(ApiKeyProvider.DTDD.pathName()).isEqualTo("dtdd");
        assertThat(ApiKeyProvider.STEAMGRIDDB.apiPath()).isEqualTo("steamgriddb-keys");
        assertThat(ApiKeyProvider.STEAMGRIDDB.pathName()).isEqualTo("steamgriddb");
        assertThat(ApiKeyProvider.ITAD.apiPath()).isEqualTo("itad-keys");
        assertThat(ApiKeyProvider.ITAD.pathName()).isEqualTo("itad");
        assertThat(ApiKeyProvider.RETROACHIEVEMENTS.apiPath()).isEqualTo("retroachievements-keys");
        assertThat(ApiKeyProvider.RETROACHIEVEMENTS.pathName()).isEqualTo("retroachievements");
        assertThat(ApiKeyProvider.OPENCRITIC.apiPath()).isEqualTo("opencritic-keys");
        assertThat(ApiKeyProvider.OPENCRITIC.pathName()).isEqualTo("opencritic");
        assertThat(ApiKeyProvider.RIOT.apiPath()).isEqualTo("riot-keys");
        assertThat(ApiKeyProvider.RIOT.pathName()).isEqualTo("riot");
        assertThat(ApiKeyProvider.FACEIT.apiPath()).isEqualTo("faceit-keys");
        assertThat(ApiKeyProvider.FACEIT.pathName()).isEqualTo("faceit");
        assertThat(ApiKeyProvider.HYPIXEL.apiPath()).isEqualTo("hypixel-keys");
        assertThat(ApiKeyProvider.HYPIXEL.pathName()).isEqualTo("hypixel");
    }

    @ParameterizedTest
    @EnumSource(ApiKeyProvider.class)
    void anApiKeyProvider_isFoundByItsPathName(ApiKeyProvider provider) {
        assertThat(ApiKeyProvider.of(provider.pathName())).isSameAs(provider);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "STEAM", "steam-keys", "xbox"})
    void anyOtherName_isNoApiKeyProvider(String name) {
        assertThat(ApiKeyProvider.of(name)).isNull();
    }

    @Test
    void twRebuilds_routeToTheirRebuildEndpoint() {
        assertThat(TwRebuild.NON_OVERRIDDEN.apiPath()).isEqualTo("rebuild");
        assertThat(TwRebuild.UNTOUCHED.apiPath()).isEqualTo("rebuild/force");
        assertThat(TwRebuild.FORCE_PINNED.apiPath()).isEqualTo("rebuild/force-pinned");
        assertThat(TwRebuild.NON_OVERRIDDEN.pathName()).isEqualTo("non-overridden");
        assertThat(TwRebuild.UNTOUCHED.pathName()).isEqualTo("untouched");
        assertThat(TwRebuild.FORCE_PINNED.pathName()).isEqualTo("force-pinned");
    }

    @ParameterizedTest
    @EnumSource(TwRebuild.class)
    void aTwRebuild_isFoundByItsPathName(TwRebuild mode) {
        assertThat(TwRebuild.of(mode.pathName())).isSameAs(mode);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "UNTOUCHED", "rebuild/force", "everything"})
    void anyOtherName_isNoTwRebuild(String name) {
        assertThat(TwRebuild.of(name)).isNull();
    }
}
