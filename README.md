# Tracksy

A project workspace for student teams and faculty guides. The upgraded application uses **React + TypeScript, Spring Boot, and PostgreSQL**, matching the stack selected from the Tracksy SRS review. Application data and credentials stay on the computer running the project.

## Run on another Windows laptop

Install **Git** and **Node.js 24 or newer** first. On 64-bit Windows, open PowerShell and run:

```powershell
git clone https://github.com/Siddhartha-x2/Tracksy.git
cd Tracksy
npm.cmd run setup
npm.cmd run dev
```

The first setup needs internet access to download the official Java 21, Maven and PostgreSQL tools. The first development launch installs the locked frontend dependencies, builds the application, and creates a new local demo database. Wait for **Tracksy is ready**, then open **http://127.0.0.1:4000/**. Keep the terminal open.

For later starts, run `npm.cmd start` from the `Tracksy` folder, or double-click `START_TRACKSY.cmd`. To rebuild after pulling source changes, stop the app with Ctrl+C, then run `git pull --ff-only`, `npm.cmd --prefix frontend ci`, and `npm.cmd run dev`.

Source code is shared through GitHub; existing local projects, uploads and credentials do not transfer with a clone. See [the laptop setup guide](docs/run-on-another-laptop.md) for troubleshooting and Docker instructions for macOS/Linux.

## Start on this Windows machine

Java 21, Maven and PostgreSQL are installed privately under work/runtime. They do not change your global PATH or install a Windows database service. The npm commands use scripts/local.mjs directly; no PowerShell execution policy changes are needed.

- npm run dev — build the TypeScript client and Spring Boot application, start the local database, and serve the application at http://localhost:4000.
- npm start — start the already built application.
- npm run setup — download the official workspace tools if this project was extracted on a new Windows computer.
- npm run build — typecheck/build the frontend and package the Java application, including its unit tests.
- npm test — run the build and security/workflow checks against a separate PostgreSQL database and backend on port 4010.

Requirements: Node.js 24+ (including npm), PowerShell for extracting the official tool archives, and outbound access to npm, Maven Central, Eclipse Adoptium and the PostgreSQL Windows binary vendor during initial setup. The local PostgreSQL server listens only on 127.0.0.1:54329. Credentials are generated randomly and stored under the ignored work/runtime directory. The local demo application binds to loopback.

## Demo workspace

Local demo accounts use TracksyDemo2026!. The login page displays the available accounts only when the backend explicitly runs with the demo profile. The faculty account is faculty@tracksy.demo; the primary student is aarav@tracksy.demo; the administrator is admin@tracksy.demo. Public registration creates student accounts only.

Students can create a proposed project and choose a faculty guide, or accept an email-bound invitation. A join code creates a pending request; it does not grant membership immediately. Faculty approval is needed before a proposed project begins accepting work.

## Application modules

- Projects, supervision approval, team leadership, responsibilities, invitations, join requests and archived membership.
- Tasks with priority, complexity, acceptance criteria, subtasks, dependencies, blockers, comments and state history.
- Private evidence files, immutable revisions, faculty approval/rejection/revision requests, quality grades and version-aware review.
- Faculty-approved milestones, weekly progress logs with retained revisions, and confidential peer evaluation.
- Versioned scoring rubrics with separate task completion, quality, consistency, peer, repository, documentation and milestone components. Reports explain the actual denominator for every component and distinguish assessment scores from shares of verified project work.
- Notifications, activity history, inactivity/deadline alerts, CSV/PDF reports, and evidence-based viva practice questions.
- Read-only public GitHub repository synchronization with verified identity mapping. GitHub OAuth account linking is configurable; it never pushes source code.
- Administrator account controls with session revocation and preservation of the last active administrator.

## Optional integrations

Copy .env.example to .env for local integration configuration. GitHub OAuth requires your own GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET and registered callback URI. Identity linking proves account ownership before repository activity can affect a student's score. This version synchronizes public repositories; private repository authorization is not implemented.

Email notifications require SMTP settings, email ownership verification and explicit user opt-in. Delivery is disabled in the local setup until credentials are configured. Notifications are available inside the app independently of email. Email delivery keeps a durable delivery record and retries a bounded number of times; SMTP cannot provide a strict exactly-once guarantee.

Viva questions are generated from approved evidence using transparent templates. They are practice prompts, not a connected AI grading service.

## Legacy data

The previous Express/SQLite implementation and its database remain in src/ and server/ as a preserved reference. They are not the upgraded application's backend. scripts/migrate-legacy.mjs prepares a transactional import from the original SQLite database. Applying the import requires an empty PostgreSQL target whose Flyway migrations have already run; it refuses to overwrite existing users. Original scrypt password hashes are retained and upgraded to BCrypt on a successful login. Missing historical quality grades remain ungraded instead of being invented.

The original standalone interface mockup is preserved under prototype/. See [the prototype notes](prototype/README.md) for its scope. Run the current application using the commands above.

## Project structure

- frontend/src/pages, components and hooks — typed React screens and reusable controls.
- backend/src/main/java/com/tracksy — security, auth, core project workflows and authorization.
- backend/src/main/java/com/tracksy/workspace — progress, evaluations, scoring, exports and repository analytics.
- backend/src/main/java/com/tracksy/integrations — GitHub identity linking and optional verified email delivery.
- backend/src/main/resources/db/migration — versioned PostgreSQL schema migrations.
- tests/workflow.test.mjs — HTTP integration checks against real PostgreSQL.
- scripts — workspace setup, build, local launch, database and legacy migration tools.
- work — ignored toolchains, local data, uploads, logs and test scratch files.

## Run with Docker

Copy .env.example to .env, set strong DB_ADMIN_PASSWORD and DB_PASSWORD values and bootstrap account credentials, then run docker compose up --build. The included compose file exposes the application only on the local machine. For a local sample workspace, set SPRING_PROFILES_ACTIVE=demo. A deployment should use the production profile, HTTPS, Secure cookies, a restricted database account and an external backup destination. Bootstrap credentials are never public registration options. Use passwords of at least 14 characters for the faculty and administrator bootstrap accounts.

## Backup and recovery

Use PostgreSQL pg_dump for the application database and back up the private upload directory at the same time. Keep database credentials and integration secrets separate from source archives. Restore backups into a new database, run compatible Flyway migrations, then restore the corresponding files before switching traffic. Run npm run backup to create a local database and upload backup under work/backups. Do not use the test database for a real project.

## Requirement evidence and limits

The accessible Tracksy GPT conversation provided a detailed implementation review and feature list. Its older SRS attachment/messages require the user's ChatGPT browser login and have not yet been fully retrieved. The implementation therefore records these requirements explicitly instead of claiming that every item in the original attachment has been certified.

The test report records checks actually run. Functional checks do not prove the SRS targets of 5,000 users, 500 projects or 100 concurrent sessions; those require a separate measured load test and deployment review. External OAuth and SMTP account configuration also requires provider credentials and live checks.

## Source archive

The new source archive includes frontend/, backend/, scripts/, tests/, docs/ and deployment configuration. It excludes local databases, uploads, credentials, dependency caches, generated builds and the preserved legacy implementation. The legacy import script expects the original SQLite file under server/data/tracksy.sqlite when it is used.

See docs/implementation.md for the architecture and requirement mapping, and docs/verification.md for checks actually completed and those still pending.

### Windows PowerShell command spelling

Type `npm.cmd start` exactly to start the already built app. For a fresh source package use `npm.cmd run setup`, then `npm.cmd run dev`. The `.cmd` suffix calls the npm command supplied with Node.js without changing PowerShell execution policy. Keep the terminal open while the app runs.

## One-click Windows startup

Double-click `START_TRACKSY.cmd` in the project folder and leave its terminal open. The launcher checks `/api/health` before announcing `Tracksy is ready: http://127.0.0.1:4000/`. Opening that URL alone does not start the server. Startup errors stay visible and are saved in `work/runtime/launcher-error.log`; Java output is saved in `work/runtime/application.log`. The launcher checks the existing database connection before trying to start PostgreSQL and refuses a second copy after a permission or timeout error.
