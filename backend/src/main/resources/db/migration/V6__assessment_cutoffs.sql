-- Stable cutoffs prevent missing-week denominators growing while submissions are locked.
ALTER TABLE projects ADD COLUMN assessment_closed_at TIMESTAMPTZ;
UPDATE projects SET assessment_closed_at=updated_at WHERE status IN ('Completed','Archived');
ALTER TABLE projects ADD CONSTRAINT project_assessment_cutoff CHECK ((status IN ('Completed','Archived')) = (assessment_closed_at IS NOT NULL));
ALTER TABLE project_members ADD COLUMN deactivated_at TIMESTAMPTZ;
UPDATE project_members SET deactivated_at=updated_at WHERE NOT active;
ALTER TABLE project_members ADD CONSTRAINT membership_assessment_cutoff CHECK (active = (deactivated_at IS NULL));
