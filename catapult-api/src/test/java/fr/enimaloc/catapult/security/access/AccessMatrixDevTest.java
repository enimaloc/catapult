package fr.enimaloc.catapult.security.access;

import org.springframework.test.context.ActiveProfiles;

/** The access matrix of the dev profile ({@code access-matrix-dev.csv}). */
@ActiveProfiles("dev")
class AccessMatrixDevTest extends AccessMatrixSupport {

    @Override
    protected String matrixResource() {
        return "access-matrix-dev.csv";
    }
}
