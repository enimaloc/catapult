package fr.enimaloc.catapult.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "app.mock.xbox-store", havingValue = "true")
public class MockXboxStoreService implements XboxStoreService {

    @Override
    public Optional<XboxProduct> fetchProduct(String productId, Locale locale) {
        return Optional.empty();
    }
}
