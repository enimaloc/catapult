package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.chat.ChatMessageSplitter;
import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A streamer's chat command, answered as plain text: what catapult's bot would have said, for the
 * chat bots taking over from it (Nightbot's {@code $(urlfetch)}, StreamElements' {@code
 * ${customapi}}, Wizebot, Streamer.bot, Twitchat) to post as is.
 *
 * <p>Not behind the {@code chat.commands} experiment: it is the way out of those commands. Only
 * the functions that read run (DynamicCommandResolver#renderReadOnly): the widget token is the only
 * access control, and whoever holds the URL is not a viewer whose role was checked.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping(path = ApiV2.PATH, version = ApiV2.VERSION)
@Tag(name = "User API v2")
public class UserApiCommandController {

    /** The bots' own limits are lower than Twitch's: the URL asks for theirs with {@code max} (bytes). */
    static final int MIN_LENGTH = 20;
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,30}$");
    private static final String ELLIPSIS = "…";

    private final WidgetTokenService widgetTokenService;
    private final DynamicCommandResolver dynamicCommandResolver;

    @Operation(summary = "Run one of the streamer's chat commands, as plain text",
            description = "Answers what catapult's bot would reply to !{name} in chat, cut to "
                    + "\"max\" UTF-8 bytes on a word boundary. Commands that change something "
                    + "(the game, a ban, a message...) answer nothing here.")
    @ApiResponse(responseCode = "200", description = "The command's answer: empty when it had "
            + "none, or there is no such enabled command (a bot posts nothing then).")
    @ApiResponse(responseCode = "404", description = "Unknown widget token.", content = @Content)
    @CrossOrigin(origins = "*")
    @GetMapping(path = "/command/{uuid}/{name}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> command(
            @Parameter(description = "Per-user widget token — the sole access control for this "
                    + "public endpoint.", in = ParameterIn.PATH, example = ApiV2.GENERIC_UUID_EXAMPLE,
                    schema = @Schema(defaultValue = ApiV2.GENERIC_UUID_EXAMPLE))
            @PathVariable UUID uuid,
            @Parameter(description = "The command's name, without its \"!\".", example = "game")
            @PathVariable String name,
            @Parameter(description = "What followed the command in chat, space-separated.")
            @RequestParam(name = "args", required = false) String args,
            @Parameter(description = "Longest answer the calling bot can post, in UTF-8 bytes "
                    + "(Nightbot: under 400 characters, StreamElements: 400 bytes).")
            @RequestParam(name = "max", defaultValue = "" + ChatMessageSplitter.TWITCH_MAX_LEN) int max) {
        Optional<UserAccount> user = widgetTokenService.resolve(uuid);
        if (user.isEmpty()) return ResponseEntity.notFound().build();

        Optional<String> answer = NAME.matcher(name).matches()
                ? dynamicCommandResolver.renderReadOnly(user.get(), "!" + name, split(args))
                : Optional.empty();
        int limit = Math.clamp(max, MIN_LENGTH, ChatMessageSplitter.TWITCH_MAX_LEN);
        return ResponseEntity.ok(answer.map(text -> cut(text.strip(), limit)).orElse(""));
    }

    private static List<String> split(String args) {
        if (args == null || args.isBlank()) return List.of();
        return Arrays.stream(args.strip().split("\\s+")).toList();
    }

    /**
     * {@code text} within {@code limit} UTF-8 bytes, cut after its last whole word. Bytes, not
     * characters: StreamElements truncates there, and a text never has more characters than bytes.
     */
    static String cut(String text, int limit) {
        if (utf8Length(text) <= limit) return text;
        int budget = limit - utf8Length(ELLIPSIS);
        int end = 0;
        for (int used = 0; end < text.length(); ) {
            int cp = text.codePointAt(end);
            int size = utf8Length(Character.toString(cp));
            if (used + size > budget) break;
            used += size;
            end += Character.charCount(cp);
        }
        int space = text.lastIndexOf(' ', end);
        return text.substring(0, space > end / 2 ? space : end).strip() + ELLIPSIS;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
