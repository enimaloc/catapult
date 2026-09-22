package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
class ApiService {
    private final ApiClient client;

    public String exchangeCode(String code) {
        Map<String, String> post = client.post("api/auth/exchange?code={code}", new ParameterizedTypeReference<>() {}, code);
        return post == null ? null : post.getOrDefault("token", null);
    }
}
