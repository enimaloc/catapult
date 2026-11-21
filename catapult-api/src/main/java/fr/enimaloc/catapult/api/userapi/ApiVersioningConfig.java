package fr.enimaloc.catapult.api.userapi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables Spring Framework 7's native API versioning (see {@link UserApiV2Controller}, mounted
 * at {@link ApiV2#PATH} with {@code version = ApiV2.BASELINE}). Version is resolved from the
 * {@code X-API-Version} request header, falling back to a {@code ?version=} query parameter
 * (for clients that cannot set a header), then to the caller's variant of the default version
 * experiment ({@link ExperimentDefaultVersionResolver}) — the resolvers are tried in declaration
 * order, so the header wins over the parameter, and both win over the experiment.
 * There is no "latest" alias path to keep in sync; when no resolver answers, the request falls back
 * to {@link ApiV2#VERSION}, by a last resolver rather than {@link ApiVersionConfigurer#setDefaultVersion}: a CORS
 * preflight then resolves no version at all and finds the CORS configuration of a route of any version (a
 * {@code "3+"} route would otherwise refuse every browser page of another origin). Beware: a path mapped at
 * several versions (a {@code "2+"} and a {@code "3"} method) then matches the preflight more than once, and Spring
 * answers such an ambiguous preflight with a permissive CORS configuration of its own, not the methods'
 * {@code @CrossOrigin}; keep the versions of one path on the same CORS rules.
 *
 * <p>Versions 2 and 3 are supported. A route unchanged since version 2 is mapped at
 * {@link ApiV2#BASELINE} ("2+") and answers both; a response that changes in version 3 gets a
 * {@link ApiV2#VERSION_3} mapping next to it, which Spring prefers for a version 3 request.
 *
 * <p>The experiment resolver is taken when the context has it: the slice tests that load this
 * configuration without the experiment beans resolve no default and serve {@link ApiV2#VERSION}.
 *
 * <p>UserApiV1Controller predates this and is deliberately not part of this scheme: it keeps its
 * own frozen "/api/game"/"/api/v1" paths (existing pasted-URL consumers — OBS browser sources,
 * Twitchat — can't attach a custom header), coexisting as a separate, unversioned controller.
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    private final ObjectProvider<ExperimentDefaultVersionResolver> defaultVersionResolver;

    public ApiVersioningConfig(ObjectProvider<ExperimentDefaultVersionResolver> defaultVersionResolver) {
        this.defaultVersionResolver = defaultVersionResolver;
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader(ApiV2.VERSION_HEADER)
                .useQueryParam(ApiV2.VERSION_PARAM);
        defaultVersionResolver.ifAvailable(resolver -> configurer.useVersionResolver(
                request -> CorsUtils.isPreFlightRequest(request) ? null : resolver.resolveVersion(request)));
        // The default, as a last resolver rather than setDefaultVersion: a CORS preflight names no version (a browser
        // never sends the header's value with it) and must match a route of any version, "3+" ones included.
        configurer.useVersionResolver(request -> CorsUtils.isPreFlightRequest(request) ? null : ApiV2.VERSION)
                .addSupportedVersions(ApiV2.VERSION, ApiV2.VERSION_3)
                .setVersionRequired(false);
    }
}
