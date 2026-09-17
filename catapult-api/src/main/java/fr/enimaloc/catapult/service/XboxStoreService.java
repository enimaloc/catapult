package fr.enimaloc.catapult.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface XboxStoreService {

    record XboxProduct(String title, String description, String publisherName, String developerName,
                       String publisherAddress, String publisherWebsiteUri, String supportUri, String supportPhone,
                       Instant lastModifiedDate, List<XboxImage> images, List<XboxVideo> videos,
                       List<String> franchises, List<String> gamePassAffirmations, String storeUrl) {

        /** A logo/box-art image for compact display, falling back to the first available image. */
        public String coverImage() {
            if (images == null || images.isEmpty()) return null;
            return images.stream()
                    .filter(i -> i.imagePurpose() != null
                            && (i.imagePurpose().equalsIgnoreCase("logo") || i.imagePurpose().equalsIgnoreCase("boxart")))
                    .map(XboxImage::uri)
                    .findFirst()
                    .orElse(images.get(0).uri());
        }
    }

    record XboxImage(String uri, String imagePurpose, int width, int height, String caption) {}

    record XboxVideo(String caption, String dashUrl, String hlsUrl, String previewImageUri, int width, int height) {}

    /**
     * Store-page product info for the given Microsoft Store product id — this is what
     * {@code DetectedGame#getSourceId()} carries for XBOX bindings (see XboxGameGetter's
     * Titlehub resolution), not the raw Xbox Live title id. Empty when the product doesn't
     * resolve or the API fails.
     */
    Optional<XboxProduct> fetchProduct(String productId, Locale locale);
}
