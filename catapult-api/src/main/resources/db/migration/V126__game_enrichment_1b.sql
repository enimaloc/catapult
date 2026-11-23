-- Pools of IsThereAnyDeal and OpenCritic (RapidAPI) API keys, same shape as steamgriddb_api_key.
CREATE TABLE itad_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account(id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);

CREATE TABLE opencritic_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account(id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);
