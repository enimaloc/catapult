package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.core.WireExchange;
import fr.enimaloc.catapult.bgs.core.WireTap;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Hands each exchange to several taps (a channel takes one): to every tap that wants it, one failing alone. */
@Slf4j
final class TeeWireTap implements WireTap {

    private final List<WireTap> taps;

    TeeWireTap(WireTap... taps) {
        this.taps = List.of(taps);
    }

    @Override
    public boolean wants() {
        return taps.stream().anyMatch(WireTap::wants);
    }

    @Override
    public void onExchange(WireExchange exchange) {
        for (WireTap tap : taps) {
            try {
                if (tap.wants()) {
                    tap.onExchange(exchange);
                }
            } catch (RuntimeException e) {
                log.warn("A BGS wire tap failed: {}", e.toString());
            }
        }
    }
}
