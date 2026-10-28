package fr.enimaloc.catapult.assets;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Where the pages load a member's avatar from ({@code ${avatars.url(channel.profileImageUrl(), 64)}}, a model
 * attribute: Thymeleaf refuses a bean reference in a URL attribute): a Twitch
 * profile picture through {@link ExternalAssetController}, at the size shown and in WebP; any other picture (the mock
 * profile's) as it is. {@code size} is one of {@link ExternalAssets#AVATAR_SIZES}, twice the CSS size it is shown at.
 */
@ControllerAdvice
public class Avatars {

    @ModelAttribute("avatars")
    public Avatars avatars() {
        return this;
    }

    public String url(String profileImageUrl, int size) {
        if (profileImageUrl == null || !profileImageUrl.startsWith(ExternalAssets.TWITCH_CDN)
                || !ExternalAssets.AVATAR_SIZES.contains(size)) {
            return profileImageUrl;
        }
        String path = profileImageUrl.substring(ExternalAssets.TWITCH_CDN.length());
        return ExternalAssets.AVATAR_PATH.matcher(path).matches() ? "/assets/ext/avatar/" + size + "/" + path : profileImageUrl;
    }
}
