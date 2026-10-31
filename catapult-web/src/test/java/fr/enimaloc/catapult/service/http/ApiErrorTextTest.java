package fr.enimaloc.catapult.service.http;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.common.error.ErrorCode;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

class ApiErrorTextTest {

    private final StaticMessageSource messages = new StaticMessageSource();
    private final ApiErrorText text = new ApiErrorText(messages);

    ApiErrorTextTest() {
        messages.addMessage("api.error.twitchat_widget_disabled", Locale.ENGLISH, "Disabled for {0}");
    }

    @Test
    void knownKey_isTheTextWithItsArguments() {
        ApiError error = new ApiError(ErrorCode.TWITCHAT_WIDGET_DISABLED, null, null);

        assertThat(text.text(error, Locale.ENGLISH, "streamer")).contains("Disabled for streamer");
    }

    @Test
    void codeWithoutAMessage_isEmpty() {
        assertThat(text.text(new ApiError(ErrorCode.CONFLICT, null, null), Locale.ENGLISH)).isEmpty();
    }

    @Test
    void sourceFallingBackToTheKey_isEmpty() {
        StaticMessageSource fallback = new StaticMessageSource();
        fallback.setUseCodeAsDefaultMessage(true);

        assertThat(new ApiErrorText(fallback).text(new ApiError(ErrorCode.CONFLICT, null, null), Locale.ENGLISH))
                .isEmpty();
    }

    @Test
    void unknownCode_orNoError_isEmpty() {
        assertThat(text.text(new ApiError(null, null, null), Locale.ENGLISH)).isEmpty();
        assertThat(text.text(null, Locale.ENGLISH)).isEmpty();
    }
}
