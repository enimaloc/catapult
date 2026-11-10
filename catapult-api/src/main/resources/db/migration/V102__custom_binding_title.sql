-- A custom binding's own stream title, winning over the channel-wide one. NULL: the channel-wide one.
ALTER TABLE custom_binding ADD COLUMN title VARCHAR(140);
