package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiMetaTagsController;
import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.service.meta.MetaTagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {ApiAdminMetaTagsController.class, ApiMetaTagsController.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false) // the admin guard is ApiSecurityConfig's, tested with it
class ApiAdminMetaTagsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MetaTagService service;

    static final String BODY = "{\"attribute\":\"PROPERTY\",\"key\":\"og:image\",\"content\":\"https://x/y.png\"}";
    static final MetaTagRequest REQUEST = new MetaTagRequest(PROPERTY, "og:image", "https://x/y.png");
    static final MetaTagDto TAG = new MetaTagDto(3, PROPERTY, "og:image", "https://x/y.png", Instant.parse("2026-10-19T10:00:00Z"));

    @Test
    void createAnswers201WithTheTag() throws Exception {
        when(service.create(any())).thenReturn(TAG);
        mvc.perform(post("/api/admin/meta-tags").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.attribute").value("PROPERTY"));
        verify(service).create(REQUEST);
    }

    @Test
    void listUpdateAndDelete() throws Exception {
        when(service.list()).thenReturn(List.of(TAG));
        when(service.update(eq(3L), any())).thenReturn(TAG);
        mvc.perform(get("/api/admin/meta-tags")).andExpect(jsonPath("$[0].key").value("og:image"));
        mvc.perform(get("/api/meta-tags")).andExpect(jsonPath("$[0].content").value("https://x/y.png"));
        mvc.perform(put("/api/admin/meta-tags/3").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(service).update(3L, REQUEST);
        mvc.perform(delete("/api/admin/meta-tags/3")).andExpect(status().isNoContent());
        verify(service).delete(3L);
    }

    @Test
    void aRefusalKeepsItsStatus() throws Exception {
        when(service.create(any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        mvc.perform(post("/api/admin/meta-tags").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }
}
