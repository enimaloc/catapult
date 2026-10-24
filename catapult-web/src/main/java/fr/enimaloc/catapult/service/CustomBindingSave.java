package fr.enimaloc.catapult.service;

/**
 * How catapult-api answered a custom binding's creation or edit: its {@code status}, the saved {@code body} when it
 * took it, else the error {@code code} to translate (name_taken, limit_reached, invalid) when it told one.
 */
public record CustomBindingSave<T>(int status, T body, String code) {

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
