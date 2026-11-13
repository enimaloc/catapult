package fr.enimaloc.catapult.bgs.client;

/**
 * Receives what a {@link BgsClient} session reports. Every method runs on the session loop: it must not block, a slow
 * consumer hands the event over to another executor.
 */
public interface BgsListener {

    /** The session is ready: logon complete and game account selected. */
    void onReady(long accountId, String battleTag);

    void onPresence(PresenceEvent event);

    void onFriend(FriendEvent event);

    /** The session ended; called once per session. */
    void onClosed(CloseReason reason);

    enum CloseReason {
        /** Transport error, end of stream, local close, or a failure before the session was ready. */
        IO,
        /** ForceDisconnect pushed by the server with an error code other than 60. */
        FORCE_DISCONNECT,
        /** ForceDisconnect 60: another session opened the same account; reconnecting would evict it in a loop. */
        SESSION_REPLACED,
        /** OnLogonComplete carried an error code, or the logon call itself was refused. */
        LOGON_FAILED,
        /** Ticket minting needs a 2FA code typed by a human. */
        CODE_REQUIRED
    }
}
