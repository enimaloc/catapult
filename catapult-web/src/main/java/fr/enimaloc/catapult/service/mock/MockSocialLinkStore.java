package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mock's social links: one store for the whole application, not per mock session, since every page
 * shows them, logged in or not. Refuses and stores as catapult-api's SocialLinkService does: an {@code https://}
 * URL (any case for the scheme, something after it, no blank or control character anywhere) of at most 2048
 * characters, a label of at most 64, kept as given (a blank one as none).
 */
class MockSocialLinkStore {
    static final int MAX_URL = 2048;
    static final int MAX_LABEL = 64;
    private static final String HTTPS = "https://";

    private final List<SocialLinkDto> links = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    MockSocialLinkStore() {
        links.add(new SocialLinkDto(ids.incrementAndGet(), SocialPlatform.GITHUB, null,
                "https://github.com/enimaloc/catapult", true, 0));
    }

    synchronized List<SocialLinkDto> list() {
        return List.copyOf(links);
    }

    synchronized int create(SocialLinkRequest r) {
        if (!valid(r)) return 400;
        links.add(link(ids.incrementAndGet(), r, links.size()));
        return 201;
    }

    synchronized int update(long id, SocialLinkRequest r) {
        int index = indexOf(id);
        if (index < 0) return 404;
        if (!valid(r)) return 400;
        links.set(index, link(id, r, links.get(index).position()));
        return 200;
    }

    synchronized int delete(long id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        links.remove(index);
        renumber();
        return 204;
    }

    /** Reorders to {@code ids}, which must be exactly the ids held. */
    synchronized int order(List<Long> ids) {
        if (ids == null || ids.size() != links.size() || ids.stream().distinct().count() != ids.size()) return 400;
        List<SocialLinkDto> ordered = new ArrayList<>();
        for (Long id : ids) {
            int index = id == null ? -1 : indexOf(id);
            if (index < 0) return 400;
            ordered.add(links.get(index));
        }
        links.clear();
        links.addAll(ordered);
        renumber();
        return 200;
    }

    private int indexOf(long id) {
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).id() == id) return i;
        }
        return -1;
    }

    private void renumber() {
        for (int i = 0; i < links.size(); i++) {
            SocialLinkDto l = links.get(i);
            links.set(i, new SocialLinkDto(l.id(), l.platform(), l.label(), l.url(), l.visible(), i));
        }
    }

    private static SocialLinkDto link(long id, SocialLinkRequest r, int position) {
        String label = r.label() == null || r.label().isBlank() ? null : r.label();
        return new SocialLinkDto(id, r.platform(), label, r.url(), r.visible(), position);
    }

    private static boolean valid(SocialLinkRequest r) {
        if (r == null || r.platform() == null) return false;
        String url = r.url();
        return url != null && url.length() <= MAX_URL && url.length() > HTTPS.length()
                && url.regionMatches(true, 0, HTTPS, 0, HTTPS.length())
                && url.chars().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
                && (r.label() == null || r.label().length() <= MAX_LABEL);
    }
}
