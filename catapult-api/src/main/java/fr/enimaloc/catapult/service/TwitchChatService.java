package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;

public interface TwitchChatService {
    void connect(UserAccount user);
    void disconnect(UserAccount user);
    void sendMessage(UserAccount user, String message);
    void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason);
    void ban(UserAccount user, String targetLogin, String reason);
    void unban(UserAccount user, String targetLogin);
}
