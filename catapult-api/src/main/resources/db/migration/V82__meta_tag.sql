-- Meta tags catapult-web adds to the head of every page of the site (<meta name|property="key"
-- content="…">: site verifications, theme-color, og:image…), edited from the admin page's Meta tab.
CREATE TABLE meta_tag (
    id BIGSERIAL PRIMARY KEY,
    attribute VARCHAR(16) NOT NULL,
    tag_key VARCHAR(100) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uq_meta_tag_attribute_key ON meta_tag (attribute, LOWER(tag_key));
