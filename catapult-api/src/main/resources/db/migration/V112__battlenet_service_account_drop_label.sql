-- A Battle.net bot is shown by its BattleTag (its masked e-mail before the first login): the free-text label is gone.
ALTER TABLE battlenet_service_account DROP COLUMN label;
