-- A Minecraft service account is shown by its Minecraft username: the free-text label is gone.
ALTER TABLE minecraft_service_account DROP COLUMN label;
