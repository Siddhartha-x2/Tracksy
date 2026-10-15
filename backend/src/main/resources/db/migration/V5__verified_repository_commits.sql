-- GitHub display-name/email attribution alone can be forged. Keep all public activity
-- visible, but give optional repository score credit only to signed commits whose
-- author and committer IDs both match the student's OAuth verified account.
ALTER TABLE repository_commits ADD COLUMN github_author_id BIGINT;
ALTER TABLE repository_commits ADD COLUMN github_committer_id BIGINT;
ALTER TABLE repository_commits ADD COLUMN signature_verified BOOLEAN NOT NULL DEFAULT FALSE;
