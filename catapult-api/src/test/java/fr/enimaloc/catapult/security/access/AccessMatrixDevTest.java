package fr.enimaloc.catapult.security.access;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.context.ActiveProfiles;

/**
 * The access matrix of the dev profile: the production matrix ({@code access-matrix.csv}), with the rows of
 * {@code access-matrix-dev-overrides.csv} replacing the production row of the same method and pattern, or added when
 * there is none (the dev-only routes).
 */
@ActiveProfiles("dev")
class AccessMatrixDevTest extends AccessMatrixSupport {

    private static final String OVERRIDES = "access-matrix-dev-overrides.csv";

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
                rows.add(override);
            }
        }
        return rows;
    }
}
