import crypto from 'node:crypto';
import { promisify } from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import helmet from 'helmet';
import multer from 'multer';
import rateLimit from 'express-rate-limit';
import { DatabaseSync } from 'node:sqlite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(__dirname, '..');
const dataDirectory = path.join(__dirname, 'data');
const uploadDirectory = path.join(__dirname, 'uploads');
fs.mkdirSync(dataDirectory, { recursive: true });
fs.mkdirSync(uploadDirectory, { recursive: true });

const db = new DatabaseSync(path.join(dataDirectory, 'tracksy.sqlite'));
db.exec(fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8'));
db.exec('PRAGMA foreign_keys = ON');

const scrypt = promisify(crypto.scrypt);
const app = express();
const port = Number(process.env.PORT || 4000);
const sessionDays = 7;
const cookieName = 'tracksy_session';
const allowedOrigins = new Set((process.env.APP_ORIGINS || 'http://localhost:4000,http://127.0.0.1:4000,http://localhost:5173,http://127.0.0.1:5173').split(',').map((origin) => origin.trim()));

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function nowIso() { return new Date().toISOString(); }
function id(prefix) { return `${prefix}${crypto.randomBytes(5).toString('hex').toUpperCase()}`; }
function publicUser(user) { return { id: user.id, name: user.name, email: user.email, role: user.role }; }
function tokenDigest(token) { return crypto.createHash('sha256').update(token).digest('hex'); }
function cookie(token, maxAge) {
  const secure = process.env.COOKIE_SECURE === 'true' ? '; Secure' : '';
  return `${cookieName}=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secure}`;
}

async function passwordHash(password, salt = crypto.randomBytes(16).toString('hex')) {
  const key = await scrypt(password, salt, 64);
  return { salt, hash: Buffer.from(key).toString('hex') };
}

async function verifyPassword(password, stored) {
  const [salt, expected] = String(stored).split(':');
  if (!salt || !expected) return false;
  const { hash } = await passwordHash(password, salt);
  const a = Buffer.from(hash, 'hex');
  const b = Buffer.from(expected, 'hex');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function storePassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const key = crypto.scryptSync(password, salt, 64);
  return `${salt}:${key.toString('hex')}`;
}

function seedDemoData() {
  if (db.prepare('SELECT COUNT(*) AS count FROM users').get().count > 0) return;
  if (process.env.NODE_ENV === 'production' && process.env.SEED_DEMO_DATA !== 'true') {
    const adminName = String(process.env.BOOTSTRAP_FACULTY_NAME || '').trim();
    const adminEmail = String(process.env.BOOTSTRAP_FACULTY_EMAIL || '').trim().toLowerCase();
    const adminPassword = String(process.env.BOOTSTRAP_FACULTY_PASSWORD || '');
    if (adminName.length < 2 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(adminEmail) || adminPassword.length < 14) {
      throw new Error('No users exist. Set BOOTSTRAP_FACULTY_NAME, BOOTSTRAP_FACULTY_EMAIL, and a 14+ character BOOTSTRAP_FACULTY_PASSWORD to create the first faculty account.');
    }
    db.prepare('INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, ?, \'faculty\')')
      .run(adminName, adminEmail, storePassword(adminPassword));
    console.log(`Created the initial faculty account for ${adminEmail}.`);
    return;
  }

  const insertUser = db.prepare('INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, ?, ?)');
  const demoPassword = storePassword('TracksyDemo2026!');
  const demoUsers = [
    ['Dr. Jamie Kim', 'faculty@tracksy.demo', 'faculty'],
    ['Aarav Rao', 'aarav@tracksy.demo', 'student'],
    ['Mia Chen', 'mia@tracksy.demo', 'student'],
    ['Noah Patel', 'noah@tracksy.demo', 'student'],
    ['Priya Shah', 'priya@tracksy.demo', 'student'],
    ['Leo Martin', 'leo@tracksy.demo', 'student'],
  ];
  db.exec('BEGIN');
  try {
    for (const [name, email, role] of demoUsers) insertUser.run(name, email, demoPassword, role);
    const users = Object.fromEntries(db.prepare('SELECT id, email FROM users').all().map((row) => [row.email, row.id]));
    db.prepare('INSERT INTO projects (id, name, description, term, join_code, faculty_owner_id) VALUES (?, ?, ?, ?, ?, ?)')
      .run('P101', 'Campus Connect', 'A campus community app for events, clubs, and the people who make them happen.', 'Spring term', 'CAMPUS26', users['faculty@tracksy.demo']);
    const addMember = db.prepare('INSERT INTO project_members (project_id, user_id) VALUES (?, ?)');
    for (const userId of Object.values(users)) addMember.run('P101', userId);

    const addTask = db.prepare(`INSERT INTO tasks (id, project_id, name, assigned_to, created_by, due_date, priority, points, status)
      VALUES (?, 'P101', ?, ?, ?, ?, ?, ?, ?)`);
    const facultyId = users['faculty@tracksy.demo'];
    [
      ['T101', 'Design the onboarding flow', users['aarav@tracksy.demo'], '2026-10-08', 'High', 5, 'In progress'],
      ['T102', 'Build event discovery cards', users['mia@tracksy.demo'], '2026-10-10', 'High', 5, 'In progress'],
      ['T103', 'Interview 5 club organizers', users['aarav@tracksy.demo'], '2026-10-06', 'Medium', 3, 'In progress'],
      ['T104', 'Set up project repository', users['noah@tracksy.demo'], '2026-10-04', 'Low', 1, 'Done'],
      ['T105', 'Prepare the user research summary', users['priya@tracksy.demo'], '2026-10-11', 'Medium', 3, 'In progress'],
      ['T106', 'Create the visual design system', users['leo@tracksy.demo'], '2026-10-12', 'Medium', 3, 'In progress'],
    ].forEach((row) => addTask.run(...row.slice(0, 1), row[1], row[2], facultyId, ...row.slice(3)));

    const addEvidence = db.prepare(`INSERT INTO evidence (id, task_id, student_id, status, current_version)
      VALUES (?, ?, ?, ?, 1)`);
    const addVersion = db.prepare(`INSERT INTO evidence_versions (evidence_id, version, title, note, work_link)
      VALUES (?, 1, ?, ?, '')`);
    const seedEvidence = [
      ['E101', 'T104', users['noah@tracksy.demo'], 'Approved', 'Repository setup and README', 'Project structure, README and contribution guide are in the repository.'],
      ['E102', 'T101', users['aarav@tracksy.demo'], 'Under review', 'First onboarding wireframes', 'Three initial screens for account setup and club selection.'],
      ['E103', 'T103', users['aarav@tracksy.demo'], 'Under review', 'Organizer interview notes', 'Notes from the first two organizer interviews.'],
      ['E104', 'T105', users['priya@tracksy.demo'], 'Changes requested', 'User research summary', 'Initial summary of the organizer interviews.'],
    ];
    for (const [evidenceId, taskId, studentId, status, title, note] of seedEvidence) {
      addEvidence.run(evidenceId, taskId, studentId, status);
      const version = addVersion.run(evidenceId, title, note);
      if (status !== 'Under review') {
        db.prepare('INSERT INTO evidence_reviews (evidence_version_id, faculty_id, decision, feedback) VALUES (?, ?, ?, ?)')
          .run(Number(version.lastInsertRowid), facultyId, status, status === 'Approved' ? 'Verified contribution.' : 'Please add takeaways from the remaining interviews.');
      }
    }
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

seedDemoData();
db.prepare("DELETE FROM sessions WHERE expires_at <= ?").run(nowIso());

app.disable('x-powered-by');
app.use(helmet({ contentSecurityPolicy: { directives: { styleSrc: ["'self'", "'unsafe-inline'"] } } }));
app.use(express.json({ limit: '1mb' }));
app.use((req, _res, next) => {
  if (['POST', 'PATCH', 'PUT', 'DELETE'].includes(req.method)) {
    const origin = req.get('origin');
    if (origin && !allowedOrigins.has(origin)) return next(new HttpError(403, 'Request origin is not allowed.'));
  }
  next();
});

const loginLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 20, standardHeaders: true, legacyHeaders: false });
const allowedFiles = new Map([
  ['.pdf', 'application/pdf'], ['.png', 'image/png'], ['.jpg', 'image/jpeg'], ['.jpeg', 'image/jpeg'],
  ['.txt', 'text/plain'], ['.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
]);
const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, callback) => callback(null, uploadDirectory),
    filename: (_req, file, callback) => callback(null, `${crypto.randomUUID()}${path.extname(file.originalname).toLowerCase()}`),
  }),
  limits: { fileSize: 10 * 1024 * 1024, files: 1 },
  fileFilter: (_req, file, callback) => {
    const ext = path.extname(file.originalname).toLowerCase();
    callback(allowedFiles.get(ext) === file.mimetype ? null : new HttpError(400, 'Upload a PDF, PNG, JPG, TXT, or DOCX file.'), allowedFiles.get(ext) === file.mimetype);
  },
});

function readCookie(req, name) {
  const value = req.headers.cookie?.split(';').map((part) => part.trim()).find((part) => part.startsWith(`${name}=`));
  return value ? decodeURIComponent(value.slice(name.length + 1)) : '';
}

function authRequired(req, _res, next) {
  const token = readCookie(req, cookieName);
  if (!token) return next(new HttpError(401, 'Sign in to continue.'));
  const session = db.prepare(`SELECT users.id, users.name, users.email, users.role
    FROM sessions JOIN users ON users.id = sessions.user_id
    WHERE sessions.token_hash = ? AND sessions.expires_at > ?`).get(tokenDigest(token), nowIso());
  if (!session) return next(new HttpError(401, 'Your session expired. Sign in again.'));
  req.user = session;
  req.sessionToken = token;
  next();
}

function facultyRequired(req, _res, next) {
  if (req.user.role !== 'faculty') return next(new HttpError(403, 'Faculty access is required for this action.'));
  next();
}

function projectMemberRequired(req, _res, next) {
  const projectId = req.params.projectId || req.params.id;
  const member = db.prepare('SELECT 1 FROM project_members WHERE project_id = ? AND user_id = ?').get(projectId, req.user.id);
  if (!member) return next(new HttpError(404, 'Project not found.'));
  req.projectId = projectId;
  next();
}

function facultyProjectRequired(req, _res, next) {
  if (req.user.role !== 'faculty') return next(new HttpError(403, 'Faculty access is required for this action.'));
  const member = db.prepare('SELECT 1 FROM project_members WHERE project_id = ? AND user_id = ?').get(req.projectId, req.user.id);
  if (!member) return next(new HttpError(404, 'Project not found.'));
  next();
}

function getLatestVersion(evidenceId) {
  return db.prepare(`SELECT evidence_versions.* FROM evidence_versions
    JOIN evidence ON evidence.id = evidence_versions.evidence_id AND evidence.current_version = evidence_versions.version
    WHERE evidence.id = ?`).get(evidenceId);
}

function evidenceForUser(evidenceId, user) {
  const item = db.prepare(`SELECT e.*, t.project_id, t.name AS task_name
    FROM evidence e JOIN tasks t ON t.id = e.task_id WHERE e.id = ?`).get(evidenceId);
  if (!item) throw new HttpError(404, 'Evidence not found.');
  const membership = db.prepare('SELECT 1 FROM project_members WHERE project_id = ? AND user_id = ?').get(item.project_id, user.id);
  if (!membership || (user.role === 'student' && item.student_id !== user.id)) throw new HttpError(404, 'Evidence not found.');
  return item;
}

function evidenceDto(item) {
  const version = getLatestVersion(item.id);
  const review = version && db.prepare('SELECT decision, feedback, reviewed_at FROM evidence_reviews WHERE evidence_version_id = ?').get(version.id);
  return {
    id: item.id, taskId: item.task_id, taskName: item.task_name, studentId: item.student_id,
    studentName: item.student_name, status: item.status, title: version?.title || '', note: version?.note || '',
    workLink: version?.work_link || '', hasFile: Boolean(version?.stored_filename),
    originalFilename: version?.original_filename || '', version: item.current_version,
    submittedAt: version?.submitted_at || item.created_at,
    feedback: review?.feedback || '', reviewDecision: review?.decision || '',
  };
}

function projectEvidence(projectId, user) {
  const ownershipFilter = user.role === 'student' ? 'AND e.student_id = ?' : '';
  const parameters = user.role === 'student' ? [projectId, user.id] : [projectId];
  const rows = db.prepare(`SELECT e.*, t.name AS task_name, u.name AS student_name
    FROM evidence e JOIN tasks t ON t.id = e.task_id JOIN users u ON u.id = e.student_id
    WHERE t.project_id = ? ${ownershipFilter} ORDER BY e.updated_at DESC`).all(...parameters);
  return rows.map(evidenceDto);
}

function createSession(userId, res) {
  const token = crypto.randomBytes(32).toString('base64url');
  const expiresAt = new Date(Date.now() + sessionDays * 24 * 60 * 60 * 1000).toISOString();
  db.prepare('INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)').run(tokenDigest(token), userId, expiresAt);
  res.setHeader('Set-Cookie', cookie(token, sessionDays * 24 * 60 * 60));
}

app.get('/api/health', (_req, res) => res.json({ status: 'ok', app: 'Tracksy' }));

app.post('/api/auth/register', loginLimiter, async (req, res) => {
  const name = String(req.body.name || '').trim();
  const email = String(req.body.email || '').trim().toLowerCase();
  const password = String(req.body.password || '');
  if (name.length < 2 || name.length > 100) throw new HttpError(400, 'Enter your name.');
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) throw new HttpError(400, 'Enter a valid email address.');
  if (password.length < 10 || password.length > 128) throw new HttpError(400, 'Use a password that is at least 10 characters long.');
  const hashed = await passwordHash(password);
  try {
    const result = db.prepare('INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, ?, \'student\')').run(name, email, `${hashed.salt}:${hashed.hash}`);
    createSession(Number(result.lastInsertRowid), res);
    res.status(201).json({ user: publicUser({ id: Number(result.lastInsertRowid), name, email, role: 'student' }) });
  } catch (error) {
    if (String(error.message).includes('UNIQUE constraint failed: users.email')) throw new HttpError(409, 'An account with this email already exists.');
    throw error;
  }
});

app.post('/api/auth/login', loginLimiter, async (req, res) => {
  const email = String(req.body.email || '').trim().toLowerCase();
  const password = String(req.body.password || '');
  const user = db.prepare('SELECT * FROM users WHERE email = ?').get(email);
  if (!user || !(await verifyPassword(password, user.password_hash))) throw new HttpError(401, 'Email or password is incorrect.');
  createSession(user.id, res);
  res.json({ user: publicUser(user) });
});

app.get('/api/auth/me', authRequired, (req, res) => res.json({ user: publicUser(req.user) }));
app.post('/api/auth/logout', authRequired, (req, res) => {
  db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(tokenDigest(req.sessionToken));
  res.setHeader('Set-Cookie', cookie('', 0));
  res.status(204).end();
});

app.get('/api/projects', authRequired, (req, res) => {
  const projects = db.prepare(`SELECT p.id, p.name, p.description, p.term, p.join_code, p.faculty_owner_id,
      pm.joined_at, (SELECT COUNT(*) FROM project_members m WHERE m.project_id = p.id) AS member_count,
      (SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id) AS task_count
    FROM projects p JOIN project_members pm ON pm.project_id = p.id
    WHERE pm.user_id = ? ORDER BY p.created_at DESC`).all(req.user.id);
  res.json({ projects });
});

app.post('/api/projects', authRequired, facultyRequired, (req, res) => {
  const name = String(req.body.name || '').trim();
  const description = String(req.body.description || '').trim();
  const term = String(req.body.term || '').trim();
  if (name.length < 2 || name.length > 120) throw new HttpError(400, 'Project name must be between 2 and 120 characters.');
  const projectId = id('P');
  const joinCode = crypto.randomBytes(4).toString('hex').toUpperCase();
  db.prepare('INSERT INTO projects (id, name, description, term, join_code, faculty_owner_id) VALUES (?, ?, ?, ?, ?, ?)')
    .run(projectId, name, description, term, joinCode, req.user.id);
  db.prepare('INSERT INTO project_members (project_id, user_id) VALUES (?, ?)').run(projectId, req.user.id);
  res.status(201).json({ project: { id: projectId, name, description, term, joinCode, memberCount: 1, taskCount: 0 } });
});

app.post('/api/projects/join', authRequired, (req, res) => {
  if (req.user.role !== 'student') throw new HttpError(403, 'Only students can join a project with a join code.');
  const code = String(req.body.joinCode || '').trim().toUpperCase();
  const project = db.prepare('SELECT id, name FROM projects WHERE join_code = ?').get(code);
  if (!project) throw new HttpError(404, 'That project code was not found. Ask your faculty guide for the correct code.');
  db.prepare('INSERT OR IGNORE INTO project_members (project_id, user_id) VALUES (?, ?)').run(project.id, req.user.id);
  res.json({ project });
});

app.get('/api/projects/:projectId/team', authRequired, projectMemberRequired, (req, res) => {
  const members = db.prepare(`SELECT u.id, u.name, u.email, u.role, pm.joined_at
    FROM project_members pm JOIN users u ON u.id = pm.user_id
    WHERE pm.project_id = ? ORDER BY u.role DESC, u.name`).all(req.projectId);
  res.json({ members: members.map((member) => ({ ...member, email: req.user.role === 'faculty' ? member.email : undefined })) });
});

app.get('/api/projects/:projectId/tasks', authRequired, projectMemberRequired, (req, res) => {
  const assignmentFilter = req.user.role === 'student' ? 'AND t.assigned_to = ?' : '';
  const parameters = req.user.role === 'student' ? [req.projectId, req.user.id] : [req.projectId];
  const tasks = db.prepare(`SELECT t.*, assignee.name AS assignee_name, creator.name AS creator_name,
      (SELECT e.status FROM evidence e WHERE e.task_id = t.id AND e.student_id = t.assigned_to) AS evidence_status
    FROM tasks t JOIN users assignee ON assignee.id = t.assigned_to JOIN users creator ON creator.id = t.created_by
    WHERE t.project_id = ? ${assignmentFilter} ORDER BY CASE t.status WHEN 'In progress' THEN 0 WHEN 'Under review' THEN 1 WHEN 'To do' THEN 2 ELSE 3 END, t.due_date`).all(...parameters);
  res.json({ tasks });
});

app.post('/api/projects/:projectId/tasks', authRequired, projectMemberRequired, facultyProjectRequired, (req, res) => {
  const name = String(req.body.name || '').trim();
  const details = String(req.body.details || '').trim();
  const assigneeId = Number(req.body.assignedTo);
  const priority = ['Low', 'Medium', 'High'].includes(req.body.priority) ? req.body.priority : 'Medium';
  const dueDate = req.body.dueDate ? String(req.body.dueDate) : null;
  if (name.length < 2 || name.length > 160) throw new HttpError(400, 'Task name must be between 2 and 160 characters.');
  if (dueDate && !/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) throw new HttpError(400, 'Enter a valid due date.');
  const assigned = db.prepare('SELECT 1 FROM project_members WHERE project_id = ? AND user_id = ?').get(req.projectId, assigneeId);
  if (!assigned) throw new HttpError(400, 'Choose a member of this project.');
  const points = { Low: 1, Medium: 3, High: 5 }[priority];
  const taskId = id('T');
  db.prepare(`INSERT INTO tasks (id, project_id, name, details, assigned_to, created_by, due_date, priority, points)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(taskId, req.projectId, name, details, assigneeId, req.user.id, dueDate, priority, points);
  const task = db.prepare(`SELECT t.*, u.name AS assignee_name, creator.name AS creator_name
    FROM tasks t JOIN users u ON u.id = t.assigned_to JOIN users creator ON creator.id = t.created_by WHERE t.id = ?`).get(taskId);
  res.status(201).json({ task });
});

app.patch('/api/tasks/:taskId', authRequired, (req, res) => {
  const task = db.prepare('SELECT * FROM tasks WHERE id = ?').get(req.params.taskId);
  if (!task) throw new HttpError(404, 'Task not found.');
  const membership = db.prepare('SELECT 1 FROM project_members WHERE project_id = ? AND user_id = ?').get(task.project_id, req.user.id);
  if (!membership) throw new HttpError(404, 'Task not found.');
  if (req.user.role !== 'faculty') throw new HttpError(403, 'Faculty access is required to update task status.');
  const status = String(req.body.status || '');
  if (!['To do', 'In progress', 'Under review', 'Done'].includes(status)) throw new HttpError(400, 'Choose a valid task status.');
  db.prepare('UPDATE tasks SET status = ? WHERE id = ?').run(status, task.id);
  res.json({ task: { ...task, status } });
});

app.get('/api/projects/:projectId/evidence', authRequired, projectMemberRequired, (req, res) => {
  res.json({ evidence: projectEvidence(req.projectId, req.user) });
});

function validateEvidenceSubmission(req, _res, next) {
  const task = db.prepare('SELECT * FROM tasks WHERE id = ?').get(req.params.taskId);
  if (!task || task.project_id !== req.projectId) return next(new HttpError(404, 'Task not found.'));
  if (task.assigned_to !== req.user.id || req.user.role !== 'student') return next(new HttpError(403, 'Only the assigned student can submit evidence for this task.'));
  const existing = db.prepare('SELECT id, status FROM evidence WHERE task_id = ? AND student_id = ?').get(task.id, req.user.id);
  if (existing) return next(new HttpError(409, 'Evidence already exists for this task. Update that submission if faculty requested changes.'));
  req.task = task;
  next();
}

function validateResubmission(req, _res, next) {
  try {
    const evidence = evidenceForUser(req.params.evidenceId, req.user);
    if (req.user.role !== 'student' || evidence.student_id !== req.user.id) throw new HttpError(403, 'Only the submitting student can update this evidence.');
    if (evidence.status !== 'Changes requested') throw new HttpError(409, 'Faculty must request changes before a submission can be updated.');
    req.evidence = evidence;
    next();
  } catch (error) { next(error); }
}

function submissionFields(req) {
  const title = String(req.body.title || '').trim();
  const note = String(req.body.note || '').trim();
  const workLink = String(req.body.workLink || '').trim();
  if (title.length < 2 || title.length > 160) throw new HttpError(400, 'Evidence title must be between 2 and 160 characters.');
  if (note.length > 4000) throw new HttpError(400, 'Evidence notes must be under 4,000 characters.');
  if (workLink) {
    try { const url = new URL(workLink); if (!['http:', 'https:'].includes(url.protocol)) throw new Error(); }
    catch { throw new HttpError(400, 'Work link must use http or https.'); }
  }
  if (!req.file && !workLink && !note) throw new HttpError(400, 'Add a note, link, or file to show your work.');
  return { title, note, workLink };
}

function insertEvidenceVersion(evidenceId, version, fields, file) {
  db.prepare(`INSERT INTO evidence_versions (evidence_id, version, title, note, work_link, original_filename, stored_filename)
    VALUES (?, ?, ?, ?, ?, ?, ?)`).run(evidenceId, version, fields.title, fields.note, fields.workLink,
    file?.originalname || null, file?.filename || null);
}

app.post('/api/projects/:projectId/tasks/:taskId/evidence', authRequired, projectMemberRequired, validateEvidenceSubmission, upload.single('file'), (req, res) => {
  const fields = submissionFields(req);
  const evidenceId = id('E');
  db.exec('BEGIN');
  try {
    db.prepare('INSERT INTO evidence (id, task_id, student_id, status, current_version) VALUES (?, ?, ?, \'Under review\', 1)')
      .run(evidenceId, req.task.id, req.user.id);
    insertEvidenceVersion(evidenceId, 1, fields, req.file);
    db.prepare("UPDATE tasks SET status = 'Under review' WHERE id = ? AND status = 'To do'").run(req.task.id);
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    if (req.file) fs.rmSync(req.file.path, { force: true });
    throw error;
  }
  res.status(201).json({ evidence: projectEvidence(req.projectId, req.user).find((item) => item.id === evidenceId) });
});

app.post('/api/evidence/:evidenceId/resubmit', authRequired, validateResubmission, upload.single('file'), (req, res) => {
  const fields = submissionFields(req);
  const latest = getLatestVersion(req.evidence.id);
  const version = req.evidence.current_version + 1;
  if (!req.file && !fields.workLink && latest?.stored_filename) {
    fields.workLink = latest.work_link;
    fields.title ||= latest.title;
    fields.note ||= latest.note;
  }
  db.exec('BEGIN');
  try {
    const retainedFile = latest?.stored_filename ? { originalname: latest.original_filename, filename: latest.stored_filename } : null;
    insertEvidenceVersion(req.evidence.id, version, fields, req.file || retainedFile);
    db.prepare("UPDATE evidence SET status = 'Under review', current_version = ?, updated_at = ? WHERE id = ?")
      .run(version, nowIso(), req.evidence.id);
    db.prepare("UPDATE tasks SET status = 'Under review' WHERE id = ? AND status != 'Done'").run(req.evidence.task_id);
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    if (req.file) fs.rmSync(req.file.path, { force: true });
    throw error;
  }
  res.json({ evidence: projectEvidence(req.evidence.project_id, req.user).find((item) => item.id === req.evidence.id) });
});

app.get('/api/evidence/:evidenceId/file', authRequired, (req, res) => {
  const evidence = evidenceForUser(req.params.evidenceId, req.user);
  const version = getLatestVersion(evidence.id);
  if (!version?.stored_filename) throw new HttpError(404, 'This evidence does not have an uploaded file.');
  const filePath = path.join(uploadDirectory, path.basename(version.stored_filename));
  if (!fs.existsSync(filePath)) throw new HttpError(404, 'The uploaded file is no longer available.');
  res.download(filePath, version.original_filename || 'evidence');
});

app.post('/api/evidence/:evidenceId/review', authRequired, facultyRequired, (req, res) => {
  const evidence = evidenceForUser(req.params.evidenceId, req.user);
  if (evidence.status !== 'Under review') throw new HttpError(409, 'Only submissions under review can receive a decision.');
  const decision = String(req.body.decision || '');
  const feedback = String(req.body.feedback || '').trim();
  if (!['Approved', 'Changes requested'].includes(decision)) throw new HttpError(400, 'Choose Approve or Request changes.');
  if (decision === 'Changes requested' && feedback.length < 3) throw new HttpError(400, 'Add a short note explaining the requested changes.');
  if (feedback.length > 1000) throw new HttpError(400, 'Review feedback must be under 1,000 characters.');
  const version = getLatestVersion(evidence.id);
  db.exec('BEGIN');
  try {
    db.prepare('INSERT INTO evidence_reviews (evidence_version_id, faculty_id, decision, feedback) VALUES (?, ?, ?, ?)')
      .run(version.id, req.user.id, decision, feedback);
    db.prepare('UPDATE evidence SET status = ?, updated_at = ? WHERE id = ?').run(decision, nowIso(), evidence.id);
    db.prepare('UPDATE tasks SET status = ? WHERE id = ?').run(decision === 'Approved' ? 'Done' : 'In progress', evidence.task_id);
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
  res.json({ evidence: projectEvidence(evidence.project_id, req.user).find((item) => item.id === evidence.id) });
});

app.get('/api/projects/:projectId/report', authRequired, projectMemberRequired, (req, res) => {
  const report = db.prepare(`SELECT u.id AS userId, u.name, u.role,
      COALESCE(SUM(CASE WHEN t.id IS NOT NULL THEN t.points ELSE 0 END), 0) AS assignedPoints,
      COALESCE(SUM(CASE WHEN e.status = 'Approved' THEN t.points ELSE 0 END), 0) AS approvedPoints,
      COUNT(DISTINCT CASE WHEN e.status = 'Approved' THEN t.id END) AS approvedTasks
    FROM project_members pm JOIN users u ON u.id = pm.user_id
    LEFT JOIN tasks t ON t.project_id = pm.project_id AND t.assigned_to = u.id
    LEFT JOIN evidence e ON e.task_id = t.id AND e.student_id = u.id
    WHERE pm.project_id = ? GROUP BY u.id ORDER BY approvedPoints DESC, u.name`).all(req.projectId)
    .map((member) => ({ ...member, contributionScore: member.assignedPoints ? Math.round((member.approvedPoints / member.assignedPoints) * 100) : 0 }));
  const summary = db.prepare(`SELECT COUNT(*) AS taskCount,
      SUM(CASE WHEN status = 'Done' THEN 1 ELSE 0 END) AS completedTasks,
      (SELECT COUNT(*) FROM evidence e JOIN tasks t ON t.id = e.task_id WHERE t.project_id = ? AND e.status = 'Under review') AS pendingReviews,
      (SELECT COUNT(*) FROM evidence e JOIN tasks t ON t.id = e.task_id WHERE t.project_id = ? AND e.status = 'Approved') AS approvedSubmissions
    FROM tasks WHERE project_id = ?`).get(req.projectId, req.projectId, req.projectId);
  res.json({ members: report, summary: { ...summary, completionPercent: summary.taskCount ? Math.round((summary.completedTasks / summary.taskCount) * 100) : 0 } });
});

app.use('/api', (_req, _res, next) => next(new HttpError(404, 'API route not found.')));

if (process.env.NODE_ENV === 'production') {
  const clientDirectory = path.join(projectRoot, 'dist');
  app.use(express.static(clientDirectory));
  app.get(/.*/, (_req, res) => res.sendFile(path.join(clientDirectory, 'index.html')));
}

app.use((error, _req, res, _next) => {
  if (error instanceof multer.MulterError) {
    const message = error.code === 'LIMIT_FILE_SIZE' ? 'File size must be 10 MB or less.' : 'Could not process this file upload.';
    return res.status(400).json({ error: message });
  }
  const status = error.status || 500;
  if (status >= 500) console.error(error);
  res.status(status).json({ error: status >= 500 ? 'Something went wrong. Please try again.' : error.message });
});

app.listen(port, '0.0.0.0', () => console.log(`Tracksy API listening on http://localhost:${port}`));
