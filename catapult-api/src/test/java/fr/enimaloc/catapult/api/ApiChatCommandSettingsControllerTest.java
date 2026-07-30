package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiChatCommandSettingsController.class)
class ApiChatCommandSettingsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ChatCommandSettingRepository repository;
    @MockitoBean ExperimentService experimentService;
    final ObjectMapper om = new ObjectMapper();

    private UserAccount mockUser() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("99");
        user.setTwitchUsername("streamer");
        when(userAccountRepository.findByTwitchId(any())).thenReturn(Optional.of(user));
        return user;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAdmin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb) {
        return rb
                .with(jwt().jwt(j -> j.claim("twitchId", "99"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf());
    }

    @Test
    void get_returns_the_current_users_settings() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);
        ChatCommandSetting setting = new ChatCommandSetting();
        setting.setKey("language");
        setting.setValue("fr");
        when(repository.findByUser(user)).thenReturn(List.of(setting));

        mvc.perform(withAdmin(get("/api/chat-command-settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("language"))
                .andExpect(jsonPath("$[0].value").value("fr"));
    }

    @Test
    void put_upserts_a_setting_value() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);
        when(repository.findByUserAndKey(user, "language")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(withAdmin(put("/api/chat-command-settings/{key}", "language"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "fr"))))
                .andExpect(status().isNoContent());

        verify(repository).save(any());
    }

    @Test
    void put_rejects_an_invalid_key() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        mvc.perform(withAdmin(put("/api/chat-command-settings/{key}", "not a valid key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "fr"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_rejects_reserved_prototype_pollution_keys() throws Exception {
        // JsCompiler rejects ctx.settings.__proto__/.constructor/.prototype (prototype-pollution
        // guard) — reject them here too so a command referencing such a key fails clearly at
        // save time instead of compiling fine here and only failing later at dispatch time.
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        for (String reserved : new String[]{"__proto__", "constructor", "prototype"}) {
            mvc.perform(withAdmin(put("/api/chat-command-settings/{key}", reserved))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("value", "x"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void delete_removes_the_setting() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        mvc.perform(withAdmin(delete("/api/chat-command-settings/{key}", "language")))
                .andExpect(status().isNoContent());

        verify(repository).deleteByUserAndKey(user, "language");
    }
}
