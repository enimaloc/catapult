-- A member has up to two personal API tokens: FULL (cat_) and read-only READ (catr_).
ALTER TABLE user_api_token ADD COLUMN scope VARCHAR(8) NOT NULL DEFAULT 'FULL';
ALTER TABLE user_api_token DROP CONSTRAINT user_api_token_pkey;
ALTER TABLE user_api_token ADD PRIMARY KEY (user_id, scope);
