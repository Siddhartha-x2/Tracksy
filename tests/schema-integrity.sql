-- Run on an isolated, migrated PostgreSQL database. Every fixture is rolled back.
BEGIN;
INSERT INTO users(id,name,email,password_hash,role) VALUES
 (900001,'Schema guide','schema-guide@tracksy.test','test-only','faculty'),
 (900002,'Schema student','schema-student@tracksy.test','test-only','student'),
 (900003,'Schema teammate','schema-teammate@tracksy.test','test-only','student');
INSERT INTO projects(id,name,join_code,faculty_owner_id) VALUES ('schema-check','Schema integrity','SCHEMA-CHECK',900001);
INSERT INTO project_members(project_id,user_id,project_role) VALUES
 ('schema-check',900001,'faculty'),('schema-check',900002,'leader'),('schema-check',900003,'member');
INSERT INTO tasks(id,project_id,name,assigned_to,created_by,points) VALUES ('schema-task','schema-check','Schema task',900002,900001,3);
INSERT INTO evidence(id,task_id,student_id) VALUES ('schema-evidence','schema-task',900002);
INSERT INTO evidence_versions(id,evidence_id,version,title,note) VALUES (900001,'schema-evidence',1,'Schema submission','A transaction-scoped schema fixture.');
INSERT INTO evidence_reviews(id,evidence_version_id,faculty_id,decision,quality) VALUES (900001,900001,900001,'Approved',4);
INSERT INTO weekly_logs(id,project_id,user_id,week_start,completed_work,next_plan,hours) VALUES ('schema-log','schema-check',900002,'2026-10-12','Completed schema fixture','Review schema validation',5);
INSERT INTO peer_reviews(id,project_id,reviewer_id,reviewee_id,period,collaboration,communication,reliability) VALUES (900001,'schema-check',900002,900003,'2026-10',4,4,4);
INSERT INTO score_rubrics(id,project_id,version,tasks,evidence,consistency,peer,repository,documentation,milestones,created_by) VALUES (900001,'schema-check',1,40,20,15,10,0,10,5,900001);
INSERT INTO user_github_identities(user_id,github_id,github_username) VALUES (900002,900001,'schema-student');
DO $$
DECLARE passed INTEGER := 0;
BEGIN
 BEGIN
  INSERT INTO users(name,email,password_hash,role) VALUES ('Duplicate identity','SCHEMA-STUDENT@tracksy.test','test-only','student');
  RAISE EXCEPTION 'Case-insensitive duplicate email was accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE project_members SET project_role='leader' WHERE project_id='schema-check' AND user_id=900003;
  RAISE EXCEPTION 'Multiple active project leaders were accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE tasks SET assigned_to=999999 WHERE id='schema-task';
  RAISE EXCEPTION 'A non-member task assignee was accepted';
 EXCEPTION WHEN foreign_key_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE tasks SET complexity=6 WHERE id='schema-task';
  RAISE EXCEPTION 'Invalid complexity was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO task_dependencies(task_id,depends_on_task_id) VALUES ('schema-task','schema-task');
  RAISE EXCEPTION 'Self dependency was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO evidence_versions(evidence_id,version,title) VALUES ('schema-evidence',1,'Duplicate revision');
  RAISE EXCEPTION 'Duplicate evidence version was accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO evidence_reviews(evidence_version_id,faculty_id,decision,quality) VALUES (900001,900001,'Approved',5);
  RAISE EXCEPTION 'A second review of an immutable evidence version was accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE evidence_reviews SET quality=6 WHERE id=900001;
  RAISE EXCEPTION 'Invalid quality rating was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  DELETE FROM tasks WHERE id='schema-task';
  RAISE EXCEPTION 'Evidence history was destructively removed';
 EXCEPTION WHEN foreign_key_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE weekly_logs SET week_start='2026-10-13' WHERE id='schema-log';
  RAISE EXCEPTION 'A non-Monday week start was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE weekly_logs SET hours=169 WHERE id='schema-log';
  RAISE EXCEPTION 'Impossible weekly hours were accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO weekly_logs(id,project_id,user_id,week_start,completed_work,next_plan,hours) VALUES ('schema-duplicate-log','schema-check',900002,'2026-10-12','Duplicate weekly record','Duplicate weekly plan',1);
  RAISE EXCEPTION 'Duplicate weekly log was accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE peer_reviews SET reviewee_id=reviewer_id WHERE id=900001;
  RAISE EXCEPTION 'Self evaluation was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO peer_reviews(project_id,reviewer_id,reviewee_id,period,collaboration,communication,reliability) VALUES ('schema-check',900002,900003,'2026-10',5,5,5);
  RAISE EXCEPTION 'Duplicate monthly peer evaluation was accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE score_rubrics SET tasks=41 WHERE id=900001;
  RAISE EXCEPTION 'Rubric total exceeding 100 was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO user_github_identities(user_id,github_id,github_username) VALUES (900003,900001,'schema-student');
  RAISE EXCEPTION 'A GitHub account linked to multiple users was accepted';
 EXCEPTION WHEN unique_violation THEN passed := passed+1; END;
 BEGIN
  INSERT INTO milestones(id,project_id,title,due_date,status) VALUES ('schema-milestone','schema-check','Unapproved milestone','2026-10-15','Completed');
  RAISE EXCEPTION 'An unapproved completed milestone was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE projects SET status='Completed' WHERE id='schema-check';
  RAISE EXCEPTION 'Project closure without a stable assessment cutoff was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 BEGIN
  UPDATE project_members SET active=false WHERE project_id='schema-check' AND user_id=900003;
  RAISE EXCEPTION 'Membership deactivation without a stable assessment cutoff was accepted';
 EXCEPTION WHEN check_violation THEN passed := passed+1; END;
 RAISE LOG 'PASS: % PostgreSQL integrity assertions',passed;
END $$;
ROLLBACK;
