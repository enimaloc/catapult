package fr.enimaloc.catapult.api.userapi;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables Spring Framework 7's native API versioning (see {@link UserApiV2Controller}, mounted
 * at {@link ApiV2#PATH} with {@code version = ApiV2.VERSION}). Version is resolved from the
 * {@code X-API-Version} request header, falling back to a {@code ?version=} query parameter
 * (for clients that cannot set a header), rather than baked into the URL — the resolvers are tried
 * in declaration order, so the header wins when both are present.
 * There is no "latest" alias path to keep in sync, because omitting both simply resolves to
 * {@link ApiV2#VERSION} via {@link ApiVersionConfigurer#setDefaultVersion}.
 *
 * <p>UserApiV1Controller predates this and is deliberately not part of this scheme: it keeps its
 * own frozen "/api/game"/"/api/v1" paths (existing pasted-URL consumers — OBS browser sources,
 * Twitchat — can't attach a custom header), coexisting as a separate, unversioned controller.
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader(ApiV2.VERSION_HEADER)
                .useQueryParam(ApiV2.VERSION_PARAM)
                .addSupportedVersions(ApiV2.VERSION)
                .setDefaultVersion(ApiV2.VERSION)
                .setVersionRequired(false);
    }
}
