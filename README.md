# Tracksy

Tracksy is a full-stack project contribution tracker for student teams and faculty guides. Students work from assigned tasks and submit evidence. Faculty reviews submissions, requests updates, or approves work. The contribution report is calculated from approved task points.

## Run locally

Requirements: Node.js 24 or newer and npm.

1. Install dependencies: `npm install`
2. Build the React client and start the Express app: `npm run dev`
3. Open `http://localhost:4000`

Copy `.env.example` to `.env` only if you want to change the default local configuration. `npm run dev` builds the client and serves both the app and API from Express; it does not require a separate frontend server.

SQLite creates its database at `server/data/tracksy.sqlite` the first time the API starts. Uploaded files are stored under `server/uploads/` and are only served through an authenticated API route. `npm run dev` rebuilds the client and then serves the complete app from Express.

## Demo accounts

All demo accounts use `TracksyDemo2026!`.

| Role | Email |
| --- | --- |
| Faculty guide | `faculty@tracksy.demo` |
| Student | `aarav@tracksy.demo` |
| Student | `mia@tracksy.demo` |
| Student | `noah@tracksy.demo` |
| Student | `priya@tracksy.demo` |
| Student | `leo@tracksy.demo` |

The sample Campus Connect project uses join code `CAMPUS26`. New student accounts can join with a project code. The faculty demo account can create a project and share its join code with students.

## Main workflows

- Student accounts can see their assigned tasks, submit notes, work links, and PDF/PNG/JPG/TXT/DOCX files, and update a submission after faculty requests changes.
- Faculty accounts can create tasks, assign a priority and due date, review submissions, leave feedback, approve evidence, and create projects.
- Task points are based on priority: Low = 1, Medium = 3, High = 5. A student's contribution score is their approved assigned points divided by all points assigned to them in the project.
- Reports and team membership are read from SQLite. Students only receive their own tasks and evidence; the API checks project membership and role on the server.

## Project layout

- `src/` — React app, screens, components, styling, and API client.
- `server/index.js` — Express API, authentication/session handling, authorization, and file downloads.
- `server/schema.sql` — SQLite tables, constraints, and indexes.
- `server/data/` — local SQLite database created at startup.
- `server/uploads/` — private evidence files.
- `prototype/` — the earlier static mockup, kept as a reference; it is not used by the full-stack app.
- `docs/project-brief.md` — requirements captured from the Tracksy chat and remaining scope.

## Security and scope notes

Passwords are stored with Node's `scrypt` password hashing. Sessions use random tokens stored as SHA-256 digests in SQLite and are sent in HTTP-only, SameSite cookies. Login attempts are rate-limited. All task, project, evidence, review, and file routes enforce membership and role checks on the server. Evidence uploads are size-limited to 10 MB and restricted to the listed file types.

This is an academic MVP. Before public deployment, configure HTTPS and production origins, add automated backups, consider malware scanning for uploads, and review the contribution-point policy with the faculty guide. The seeded sample accounts and project are for local demonstration only.

## Production build

Run `npm run build`, then set `APP_ORIGINS` to the deployed origin and `COOKIE_SECURE=true` before starting with `npm start`. On a new production database, provide `BOOTSTRAP_FACULTY_NAME`, `BOOTSTRAP_FACULTY_EMAIL`, and a strong `BOOTSTRAP_FACULTY_PASSWORD` so the first faculty guide can sign in and create a project. Demo accounts are seeded by `npm run dev` for local use and are not added to a fresh production database.
