-- Social links (Discord, X, GitHub…) catapult-web shows in the footer of every page, edited from the
-- admin page. The GitHub link is the one the footer carried before they became configurable.
CREATE TABLE social_link (
    id BIGSERIAL PRIMARY KEY,
    platform VARCHAR(16) NOT NULL,
    label VARCHAR(64),
    url VARCHAR(2048) NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    position INT NOT NULL
);
INSERT INTO social_link (platform, label, url, visible, position)
VALUES ('GITHUB', NULL, 'https://github.com/enimaloc/catapult', TRUE, 0);
