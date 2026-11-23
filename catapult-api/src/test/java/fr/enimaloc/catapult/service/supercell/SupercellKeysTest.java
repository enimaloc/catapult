package fr.enimaloc.catapult.service.supercell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SupercellKeysTest {

    @Test
    void aJwtShapedKey_isWellFormed_andItsClaimsAreRead() {
        String key = SupercellTestKeys.key("royale", "198.51.100.4/32", "a");

        assertThat(SupercellKeys.wellFormed(key)).isTrue();
        var claims = SupercellKeys.claims(key).orElseThrow();
        assertThat(claims.scopes()).containsExactly("royale");
        assertThat(claims.cidrs()).containsExactly("198.51.100.4/32");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"fake-key", "abc.def.ghi", "eyJabcdefghij.eyJabcdefghij", "eyJ abcdefghij.eyJabcdefghij.abcdefghij"})
    void anythingElse_isNotWellFormed(String key) {
        assertThat(SupercellKeys.wellFormed(key)).isFalse();
        assertThat(SupercellKeys.claims(key)).isEmpty();
    }

    @Test
    void aTooLongKey_isNotWellFormed() {
        String key = SupercellTestKeys.clash("x") + "a".repeat(SupercellKeys.MAX_LENGTH);
        assertThat(SupercellKeys.wellFormed(key)).isFalse();
    }

    @Test
    void anUnreadablePayload_hasNoClaims() {
        String key = SupercellTestKeys.unreadable();
        assertThat(SupercellKeys.wellFormed(key)).isTrue();
        assertThat(SupercellKeys.claims(key)).isEmpty();
    }

    @Test
    void mask_keepsFourCharactersEachSide() {
        assertThat(SupercellKeys.mask("abcdefghijkl")).isEqualTo("abcd…ijkl");
        assertThat(SupercellKeys.mask("short")).isEqualTo("…");
    }
}
