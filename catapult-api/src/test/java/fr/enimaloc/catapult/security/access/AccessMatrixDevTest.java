package fr.enimaloc.catapult.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.context.ActiveProfiles;

/**
 * The access matrix of the dev profile: the production matrix ({@code access-matrix.csv}), with the rows of
 * {@code access-matrix-dev-overrides.csv} replacing the production row of the same method and pattern, or added for
 * a dev-only route ({@code /api/dev/**}); an override of any other route that has no production row fails.
 */
@ActiveProfiles("dev")
class AccessMatrixDevTest extends AccessMatrixSupport {

    private static final String OVERRIDES = "access-matrix-dev-overrides.csv";
    private static final String DEV_ROUTES = "/api/internal/dev/";

    @Override
    protected String matrixResource() {
        return "access-matrix.csv";
    }

    @Override
    protected List<Row> matrixRows() throws IOException {
        List<Row> rows = new ArrayList<>(Row.parse(matrixResource()));
        for (Row override : Row.parse(OVERRIDES)) {
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).key().equals(override.key())) {
                    at = i;
                }
            }
            if (at >= 0) {
                rows.set(at, override);
            } else {
                assertThat(devOnly(override.key()))
                        .as("%s line %d overrides %s, which has no row in %s",
                                OVERRIDES, override.line(), override.key(), matrixResource())
                        .isTrue();
                rows.add(override);
            }
        }
        return rows;
    }

    /** A dev-only route's row is added by the overrides; any other route's row is in the production matrix. */
    @Override
    protected String rowFile(String key) {
        return devOnly(key) ? OVERRIDES : matrixResource();
    }

    private static boolean devOnly(String key) {
        return key.substring(key.indexOf(' ') + 1).startsWith(DEV_ROUTES);
    }
}
