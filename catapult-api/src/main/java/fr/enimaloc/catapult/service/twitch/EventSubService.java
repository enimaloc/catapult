package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;

public interface EventSubService {

    void connect(UserAccount user);

    void disconnect(UserAccount user);
}
