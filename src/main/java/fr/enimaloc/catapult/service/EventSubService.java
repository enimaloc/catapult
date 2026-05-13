package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;

public interface EventSubService {

    void connect(UserAccount user);

    void disconnect(UserAccount user);
}
