# Run Tracksy on another laptop

## Windows: first launch

Prerequisites: 64-bit Windows, Git and Node.js 24 or newer (including npm). Initial setup downloads several hundred megabytes of tools and needs internet access and available disk space. Java, Maven and PostgreSQL do not need separate global installations.

Open PowerShell in the folder where you want to keep the project, then paste:

```powershell
git clone https://github.com/Siddhartha-x2/Tracksy.git
cd Tracksy
npm.cmd run setup
npm.cmd run dev
```

`setup` obtains the official Windows tools under `work/runtime`. `dev` installs the locked frontend dependencies if missing, builds React/TypeScript and Spring Boot, prepares the local PostgreSQL database, and starts the app. The Java build includes the existing unit tests.

Wait until the terminal prints **Tracksy is ready**, then open:

**http://127.0.0.1:4000/**

Keep that terminal open while using Tracksy. Closing it or pressing Ctrl+C stops the web application. The database and uploaded files remain on disk.

The local launcher uses the demo profile. Choose an account on the login page, or use:

| Role | Email | Local demo password |
| --- | --- | --- |
| Faculty guide | faculty@tracksy.demo | TracksyDemo2026! |
| Student team leader | aarav@tracksy.demo | TracksyDemo2026! |
| Administrator | admin@tracksy.demo | TracksyDemo2026! |

## Later launches

Inside the cloned `Tracksy` folder:

```powershell
npm.cmd start
```

You can also double-click `START_TRACKSY.cmd`. A build must already exist, so use `npm.cmd run dev` for the first launch.

## Get future updates

Stop the current app with Ctrl+C. In its project folder:

```powershell
git pull --ff-only
npm.cmd --prefix frontend ci
npm.cmd run dev
```

The explicit `frontend ci` refreshes dependencies when a future update changes the lockfile. `dev` rebuilds and runs database migrations without replacing your existing project data. Back up real project data before applying an upgrade.

## macOS/Linux or a Docker setup

Install Git and Docker with Docker Compose. Then:

```sh
git clone https://github.com/Siddhartha-x2/Tracksy.git
cd Tracksy
cp .env.example .env
```

Edit `.env` before starting: set different strong values for `DB_ADMIN_PASSWORD` and `DB_PASSWORD`, and set `SPRING_PROFILES_ACTIVE=demo` for the local sample workspace. Leave the optional OAuth/SMTP credentials blank unless you configure those providers. The production profile needs faculty/admin bootstrap credentials instead of demo accounts; follow the README.

```sh
docker compose up --build
```

Open **http://127.0.0.1:4000/**. Later runs can use `docker compose up`. Database and upload volumes persist. Docker deployment has not yet been verified in this workspace.

## Troubleshooting

- **`git` or `node` not recognized:** install the prerequisites and open a new terminal. `node --version` must report version 24 or newer.
- **PowerShell blocks `npm.ps1`:** use `npm.cmd` exactly as shown. No execution-policy change is needed.
- **Build is missing:** run `npm.cmd run setup`, then `npm.cmd run dev` before using `npm.cmd start`.
- **Connection refused:** opening the URL does not start the app. Run the launcher, keep its window open, and wait for the readiness message.
- **Startup error:** read `work/runtime/launcher-error.log`, `application.log` and `postgresql.log`. The one-click launcher leaves errors visible.
- **Port 4000 is occupied:** check the existing server's terminal and stop that copy before starting another. The launcher reuses a healthy existing Tracksy instance.
- **Download fails:** check internet access to npm, Maven Central, Eclipse Adoptium/GitHub and the PostgreSQL Windows binary vendor, then retry setup. Do not delete an existing database to fix a download failure.

## Data and integrations

Each clone creates its own database and randomly generated local database credentials. GitHub does not synchronize local users, project records, uploads or provider secrets. Use the backup instructions if you need to move existing data. GitHub OAuth and email delivery require your own provider configuration; local demo use does not require either integration.
