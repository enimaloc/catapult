package fr.enimaloc.catapult.service.enrichment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The key per-IP limits count a client under. */
class ClientKeysTest {

    @Test
    void ipv4_unchanged() {
        assertThat(ClientKeys.of("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6_isItsSlash64() {
        assertThat(ClientKeys.of("2001:db8:0:1::a")).isEqualTo("2001:db8:0:1::/64");
        assertThat(ClientKeys.of("2001:0db8:0000:0001:ffff:ffff:ffff:ffff")).isEqualTo("2001:db8:0:1::/64");
        assertThat(ClientKeys.of("[2001:db8:0:1::b]")).isEqualTo("2001:db8:0:1::/64");
        assertThat(ClientKeys.of("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
        assertThat(ClientKeys.of("2001:db8:0:2::a")).isNotEqualTo(ClientKeys.of("2001:db8:0:1::a"));
    }

    @Test
    void ipv4Mapped_isTheIpv4Address() {
        assertThat(ClientKeys.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientKeys.of("::ffff:cb00:7107")).isEqualTo("203.0.113.7");
    }

    @Test
    void unparseable_unchanged() {
        assertThat(ClientKeys.of("not:an:address:at:all:zz")).isEqualTo("not:an:address:at:all:zz");
        assertThat(ClientKeys.of("unknown")).isEqualTo("unknown");
        assertThat(ClientKeys.of(null)).isNull();
    }
}
