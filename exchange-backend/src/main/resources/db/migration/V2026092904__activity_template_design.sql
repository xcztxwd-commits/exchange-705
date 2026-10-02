-- Existing campaigns keep their legacy presentation until explicitly edited.
ALTER TABLE activity_campaign ADD COLUMN layout_json LONGTEXT NULL;
