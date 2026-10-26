package fr.enimaloc.catapult.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** How a matrix row addresses its route: the variables each caller's request is built with. */
class AccessMatrixRowTest {

    private final AccessMatrixSupport matrix = new AccessMatrixSupport() {
        @Override
        protected String matrixResource() {
            return "unused";
        }
    };

    AccessMatrixRowTest() {
        matrix.owner = account();
        matrix.mod = account();
        matrix.member = account();
        matrix.admin = account();
    }

    private static UserAccount account() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        return account;
    }

    private static AccessMatrixSupport.Row row(String pattern, String vars) {
        return new AccessMatrixSupport.Row("test.csv", 1, "GET", pattern, vars, "", Map.of());
    }

    @Test
    void userIdIsTheOwnersAccount() {
        assertThat(row("/api/internal/users/{userId}/x", "").uri(matrix, "member"))
                .isEqualTo("/api/internal/users/" + matrix.owner.getId() + "/x");
    }

    @Test
    void userIdSelfIsTheCallersOwnAccount() {
        AccessMatrixSupport.Row row = row("/api/internal/users/{userId}/x", "userId=self");

        assertThat(row.uri(matrix, "mod")).isEqualTo("/api/internal/users/" + matrix.mod.getId() + "/x");
        assertThat(row.uri(matrix, "member")).isEqualTo("/api/internal/users/" + matrix.member.getId() + "/x");
        assertThat(row.uri(matrix, "owner")).isEqualTo("/api/internal/users/" + matrix.owner.getId() + "/x");
        assertThat(row.uri(matrix, "admin")).isEqualTo("/api/internal/users/" + matrix.admin.getId() + "/x");
    }

    @Test
    void userIdSelfIsDeadForACallerWithoutAnAccountJwt() {
        AccessMatrixSupport.Row row = row("/api/internal/users/{userId}/x", "userId=self");

        assertThat(row.uri(matrix, "loop")).isEqualTo("/api/internal/users/" + AccessMatrixSupport.DEAD + "/x");
        assertThat(row.uri(matrix, "anon")).isEqualTo("/api/internal/users/" + AccessMatrixSupport.DEAD + "/x");
        assertThat(row.uri(matrix, "ptoken")).isEqualTo("/api/internal/users/" + AccessMatrixSupport.DEAD + "/x");
    }

    @Test
    void literalUnknownUserRowsAreNotRouted() {
        assertThat(row("/api/internal/users/" + AccessMatrixSupport.DEAD + "/settings", "").routed()).isFalse();
        assertThat(row("/api/channels/nobody-here/settings", "").routed()).isFalse();
        assertThat(row("/api/internal/users/{userId}/settings", "").routed()).isTrue();
    }
}
