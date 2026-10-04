PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 100),
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('student', 'faculty')),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS projects (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 120),
  description TEXT NOT NULL DEFAULT '',
  term TEXT NOT NULL DEFAULT '',
  join_code TEXT NOT NULL UNIQUE,
  faculty_owner_id INTEGER NOT NULL REFERENCES users(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS project_members (
  project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (project_id, user_id)
);

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 160),
  details TEXT NOT NULL DEFAULT '',
  assigned_to INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES users(id),
  due_date TEXT,
  priority TEXT NOT NULL CHECK (priority IN ('Low', 'Medium', 'High')),
  points INTEGER NOT NULL DEFAULT 1 CHECK (points BETWEEN 1 AND 100),
  status TEXT NOT NULL DEFAULT 'To do' CHECK (status IN ('To do', 'In progress', 'Under review', 'Done')),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (project_id, assigned_to) REFERENCES project_members(project_id, user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS evidence (
  id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  student_id INTEGER NOT NULL REFERENCES users(id),
  status TEXT NOT NULL DEFAULT 'Under review' CHECK (status IN ('Under review', 'Approved', 'Changes requested')),
  current_version INTEGER NOT NULL DEFAULT 1 CHECK (current_version >= 1),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (task_id, student_id)
);

CREATE TABLE IF NOT EXISTS evidence_versions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  evidence_id TEXT NOT NULL REFERENCES evidence(id) ON DELETE CASCADE,
  version INTEGER NOT NULL CHECK (version >= 1),
  title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 2 AND 160),
  note TEXT NOT NULL DEFAULT '',
  work_link TEXT NOT NULL DEFAULT '',
  original_filename TEXT,
  stored_filename TEXT,
  submitted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (evidence_id, version)
);

CREATE TABLE IF NOT EXISTS evidence_reviews (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  evidence_version_id INTEGER NOT NULL UNIQUE REFERENCES evidence_versions(id) ON DELETE CASCADE,
  faculty_id INTEGER NOT NULL REFERENCES users(id),
  decision TEXT NOT NULL CHECK (decision IN ('Approved', 'Changes requested')),
  feedback TEXT NOT NULL DEFAULT '',
  reviewed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_members_user ON project_members(user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_project_status ON tasks(project_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_assigned ON tasks(assigned_to, project_id);
CREATE INDEX IF NOT EXISTS idx_evidence_task ON evidence(task_id);
CREATE INDEX IF NOT EXISTS idx_evidence_student ON evidence(student_id, status);
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
