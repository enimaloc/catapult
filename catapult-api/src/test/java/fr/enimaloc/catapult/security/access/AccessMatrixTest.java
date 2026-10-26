package fr.enimaloc.catapult.security.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** The access matrix of the production profile ({@code access-matrix.csv}). */
class AccessMatrixTest extends AccessMatrixSupport {

    @Override
    protected String matrixResource() {
        return "access-matrix.csv";
    }

    /** The callers are who the matrix says: anon is remote, loop is loopback, and a JWT names its account. */
    @Test
    void callersAreWhoTheySayTheyAre() throws Exception {
        mvc.perform(get("/api/internal/admin/flags").with(r -> {
                    r.setRemoteAddr(REMOTE);
                    return r;
                }))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/admin/flags").with(r -> {
                    r.setRemoteAddr(LOOPBACK);
                    return r;
                }))
                .andExpect(status().isOk());
        mvc.perform(get("/api/internal/auth/validate")
                        .header("Authorization", authorization.get("owner"))
                        .with(r -> {
                            r.setRemoteAddr(REMOTE);
                            return r;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.getId().toString()));
    }
}
