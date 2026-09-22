package fr.enimaloc.catapult.service.http;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import java.util.Locale;

public interface HttpClient {
    default <T> T get(String path, Class<T> responseType, Object... uriVars) {
        return get(path, responseType, null, uriVars);
    }

    default <T> T get(String path, Class<T> responseType, Locale locale, Object... uriVars) {
        return get(path, new ResponseType<>(responseType), locale, uriVars);
    }

    default <T> T get(String path, ParameterizedTypeReference<T> responseType, Object... uriVars) {
        return get(path, responseType, null, uriVars);
    }

    default <T> T get(String path, ParameterizedTypeReference<T> responseType, Locale locale, Object... uriVars) {
        return get(path, new ResponseType<T>(responseType), locale, uriVars);
    }

    default <T> T get(String path, ResponseType<T> responseType, Object... uriVars) {
        return get(path, responseType, null, uriVars);
    }

    default <T> T get(String path, ResponseType<T> responseType, Locale locale, Object... uriVars) {
        return req(HttpMethod.GET, path, responseType, locale, uriVars);
    }

    default <T> T post(String path, Class<T> responseType, Object... uriVars) {
        return post(path, responseType, null, uriVars);
    }

    default <T> T post(String path, Class<T> responseType, Locale locale, Object... uriVars) {
        return post(path, new ResponseType<>(responseType), locale, uriVars);
    }

    default <T> T post(String path, ParameterizedTypeReference<T> responseType, Object... uriVars) {
        return post(path, responseType, null, uriVars);
    }

    default <T> T post(String path, ParameterizedTypeReference<T> responseType, Locale locale, Object... uriVars) {
        return post(path, new ResponseType<T>(responseType), locale, uriVars);
    }

    default <T> T post(String path, ResponseType<T> responseType, Object... uriVars) {
        return post(path, responseType, null, uriVars);
    }

    default <T> T post(String path, ResponseType<T> responseType, Locale locale, Object... uriVars) {
        return req(HttpMethod.POST, path, responseType, locale, uriVars);
    }

    default <T> T put(String path, Class<T> responseType, Object... uriVars) {
        return put(path, responseType, null, uriVars);
    }

    default <T> T put(String path, Class<T> responseType, Locale locale, Object... uriVars) {
        return put(path, new ResponseType<>(responseType), locale, uriVars);
    }

    default <T> T put(String path, ParameterizedTypeReference<T> responseType, Object... uriVars) {
        return put(path, responseType, null, uriVars);
    }

    default <T> T put(String path, ParameterizedTypeReference<T> responseType, Locale locale, Object... uriVars) {
        return put(path, new ResponseType<T>(responseType), locale, uriVars);
    }

    default <T> T put(String path, ResponseType<T> responseType, Object... uriVars) {
        return put(path, responseType, null, uriVars);
    }

    default <T> T put(String path, ResponseType<T> responseType, Locale locale, Object... uriVars) {
        return req(HttpMethod.PUT, path, responseType, locale, uriVars);
    }

    default <T> T delete(String path, Class<T> responseType, Object... uriVars) {
        return delete(path, responseType, null, uriVars);
    }

    default <T> T delete(String path, Class<T> responseType, Locale locale, Object... uriVars) {
        return delete(path, new ResponseType<>(responseType), locale, uriVars);
    }

    default <T> T delete(String path, ParameterizedTypeReference<T> responseType, Object... uriVars) {
        return delete(path, responseType, null, uriVars);
    }

    default <T> T delete(String path, ParameterizedTypeReference<T> responseType, Locale locale, Object... uriVars) {
        return delete(path, new ResponseType<T>(responseType), locale, uriVars);
    }

    default <T> T delete(String path, ResponseType<T> responseType, Object... uriVars) {
        return delete(path, responseType, null, uriVars);
    }

    default <T> T delete(String path, ResponseType<T> responseType, Locale locale, Object... uriVars) {
        return req(HttpMethod.DELETE, path, responseType, locale, uriVars);
    }

    <T> T req(HttpMethod method, String path, ResponseType<T> responseType, Locale locale, Object... uriVars);

    record ResponseType<T>(Class<T> left, ParameterizedTypeReference<T> right) {
        public ResponseType(Class<T> clazz) {
            this(clazz, null);
        }
        public ResponseType(ParameterizedTypeReference<T> parameterizedTypeReference) {
            this(null, parameterizedTypeReference);
        }

        @Override
        public Class<T> left() {
            if (!isLeft()) throw new IllegalStateException("left component is null");
            return left;
        }

        @Override
        public ParameterizedTypeReference<T> right() {
            if (!isRight()) throw new IllegalStateException("right component is null");
            return right;
        }

        public boolean isLeft() {
            return left != null;
        }

        public boolean isRight() {
            return left != null;
        }
    }
}
