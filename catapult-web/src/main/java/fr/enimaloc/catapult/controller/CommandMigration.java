package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The Integrations tab's card moving a streamer's catapult chat commands to another chat bot: for
 * each tool, what to set up for each command. Every command becomes a fetch of catapult-api's
 * plain-text answer ({@code /api/user/command/{token}/{name}}, relayed on this site's origin), in
 * each tool's own syntax, with the longest answer it can post and its way of passing on what
 * followed the command in chat.
 *
 * <p>A command that acts (changes the game, bans...) has no row: the plain-text answer runs only
 * what reads, so no tool can take it over this way. It is listed apart instead.
 *
 * @param tools  one per chat bot, in the tab order
 * @param acting the commands no tool can take over
 */
public record CommandMigration(List<Tool> tools, List<String> acting) {

    /**
     * One command for one tool.
     *
     * @param command     the command, "!" included
     * @param role        who may run it (a SenderRole name), shown where the tool's line doesn't carry it
     * @param line        what to paste: a chat line creating the command, or the URL to fetch
     * @param extra       a second chat line setting its permission, or null
     * @param argsDropped whether the command reads what follows it, which this tool can't pass on
     */
    public record Row(String command, String role, String line, String extra, boolean argsDropped) {}

    /** A chat bot: {@code id} names its i18n keys ({@code channel.commands.migration.<id>.*}). */
    public record Tool(String id, List<Row> rows) {}

    /** How a tool takes a command: its answer's limit, its arguments, and what to paste. */
    private record Syntax(String id, int max, String args, Function<Command, Row> row) {}

    private record Command(EnabledChatCommandDto dto, String url) {}

    private static final Map<String, String> NIGHTBOT_LEVELS = Map.of("VIEWERS", "everyone",
            "FOLLOWERS", "everyone", "SUBS", "subscriber", "VIP", "twitch_vip",
            "MODERATOR", "moderator", "BROADCASTER", "owner");
    private static final Map<String, Integer> STREAMELEMENTS_LEVELS = Map.of("VIEWERS", 100,
            "FOLLOWERS", 100, "SUBS", 250, "VIP", 400, "MODERATOR", 500, "BROADCASTER", 1500);

    // Nightbot wants under 400 characters, StreamElements truncates at 400 bytes, the others are
    // left with Twitch's own 500 (the plain-text answer cuts in UTF-8 bytes, see its controller).
    private static final List<Syntax> SYNTAXES = List.of(
            new Syntax("twitchat", 500, null, c -> plain(c, c.url())),
            new Syntax("wizebot", 500, "$(args_encoded)", c -> plain(c, "$urlcall(" + c.url() + ",1)")),
            new Syntax("nightbot", 399, "$(querystring)", c -> new Row(c.dto().name(), c.dto().permission(),
                    "!commands add " + c.dto().name() + " -ul=" + NIGHTBOT_LEVELS.getOrDefault(c.dto().permission(), "everyone")
                            + " $(urlfetch " + c.url() + ")", null, false)),
            new Syntax("streamelements", 400, "$(queryescape $(1:))", c -> {
                int level = STREAMELEMENTS_LEVELS.getOrDefault(c.dto().permission(), 100);
                return new Row(c.dto().name(), c.dto().permission(),
                        "!command add " + c.dto().name() + " $(customapi " + c.url() + ")",
                        level == 100 ? null : "!command options " + c.dto().name() + " -level " + level, false);
            }),
            new Syntax("streamerbot", 500, "%rawInputUrlEncoded%", c -> plain(c, c.url())));

    private static Row plain(Command c, String line) {
        return new Row(c.dto().name(), c.dto().permission(), line, null, false);
    }

    /**
     * The card for {@code commands}, whose answers are fetched under {@code baseUrl} (the absolute
     * {@code /api/user/command/{token}/}); null when there is nothing to move.
     */
    static CommandMigration of(List<EnabledChatCommandDto> commands, String baseUrl) {
        if (commands.isEmpty() || baseUrl == null) return null;
        List<EnabledChatCommandDto> movable = commands.stream().filter(c -> !c.acts()).toList();
        List<Tool> tools = SYNTAXES.stream().map(syntax -> new Tool(syntax.id(), movable.stream().map(dto -> {
            boolean passArgs = dto.takesArgs() && syntax.args() != null;
            String url = baseUrl + dto.name().substring(1) + "?max=" + syntax.max()
                    + (passArgs ? "&args=" + syntax.args() : "");
            Row row = syntax.row().apply(new Command(dto, url));
            return dto.takesArgs() && !passArgs
                    ? new Row(row.command(), row.role(), row.line(), row.extra(), true) : row;
        }).toList())).toList();
        return new CommandMigration(tools, commands.stream().filter(EnabledChatCommandDto::acts)
                .map(EnabledChatCommandDto::name).toList());
    }
}
