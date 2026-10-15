# Tracksy verification record

Build and database checks completed on 14–15 October 2026. The latest Java/frontend build completed on 15 October at 09:26 IST with 20 passing unit tests. These checks preceded the source publication requested by the user.

## Completed checks

| Check | Result | Evidence |
| --- | --- | --- |
| Strict TypeScript compilation and Vite production build | Passed | `npm run build`; 43 client modules compiled |
| Java 21 / Spring Boot executable application build | Passed | Maven package output: `BUILD SUCCESS` |
| Java unit tests | 20 passed; 0 failed, errored or skipped | Six JUnit test classes under `backend/src/test/java` |
| PostgreSQL schema migrations | V1–V6 applied successfully in an isolated PostgreSQL 17.11 standalone database | Native PostgreSQL migration checks, without a live network connection |
| Database integrity assertions | 19 passed | `tests/schema-integrity.sql`, transaction rolled back after checks |
| Legacy import SQL | Passed schema and record-count assertions in an isolated transaction | 6 users, 1 project, 6 memberships, 6 tasks, 4 evidence records, 4 versions, 2 reviews |
| Node launcher syntax | Passed | `node --check scripts/local.mjs` |
| Spring Boot startup and live Flyway migration | Observed on 15 October | Connected to PostgreSQL, applied all six migrations, and started Tomcat on port 4000; the process subsequently exited, so stable runtime behavior is not certified |
| Source archive | Verified | Source directories retained; no database, uploads, secrets or dependency caches. The updated archive includes START_TRACKSY.cmd. |

The legacy SQLite file was read without modification. The import was validated with a rollback; it has **not** been applied to the live application database.

## What the unit tests cover

- Five input-validation tests: text bounds and null characters, strict numeric values, real calendar dates, safe link schemes/credentials, and unambiguous email/boolean values.
- Three password tests: ordinary password verification, preservation of long legacy passwords, and UTF-8 byte handling without BCrypt truncation.
- Two task authorization tests: reassignment while waiting for a write lock and comment access after membership/assignment changes. These use deterministic in-memory substitutes; they are not live database concurrency tests.
- Three assessment cutoff tests: closed project timestamps, current open-project time, and repository freshness using the declared assessment cutoff.
- Four scoring tests: bounded ratios, component weights and the distinction between assessment score and verified contribution share.
- Three export tests: CSV formula injection protection, Unicode text supported by the bundled font, and PDF wrapping/pagination/text extraction.

## What the database assertions cover

Case-insensitive email uniqueness; one active leader; membership-bound task assignment; complexity/rating/hour bounds; self dependency; immutable evidence version and review uniqueness; retained evidence foreign keys; Monday-based logs; one log per student/week; self and duplicate peer evaluation; rubric totals; one owner per verified GitHub identity; faculty-approved milestone state; and stable closure/deactivation cutoffs.

These checks validate database constraints. They do not prove all HTTP authorization paths, UI behavior, upload safety, or actual concurrent user behavior.

## Still pending

1. **Live HTTP workflow suite:** `tests/workflow.test.mjs` is prepared. It needs the running Spring Boot application and PostgreSQL. The latest attempt on 15 October passed the build and 20 unit tests, then stopped at PostgreSQL with socket permission denied; the end-to-end suite has not completed. Run `npm test` in a normal terminal; it uses `tracksy_test` and port 4010.
2. **Browser review:** desktop/mobile layouts, keyboard navigation and real student/faculty/admin flows need the app at `http://localhost:4000`. The app was observed starting successfully, but it exited before the browser review. A later integration attempt failed at PostgreSQL with socket permission denied. No browser pass or screenshot is claimed.
3. **Original SRS reconciliation:** the accessible GPT project review was used; the older full SRS messages/attachment still require the user's ChatGPT login.
4. **Provider checks:** GitHub OAuth and verified SMTP delivery need configured provider credentials. Private repository support and an AI grading provider are not implemented. Viva practice uses evidence-based templates.
5. **Deployment checks:** Docker configuration, backup restoration, malware scanning and the SRS performance targets have not been demonstrated in a live deployment. No 5,000-user or 100-session load result is claimed.

## Reproduce the checks

On this prepared Windows workspace, use `npm.cmd` in PowerShell to avoid Windows selecting the restricted npm PowerShell shim:

```shell
npm.cmd run build
npm.cmd start
```

In another normal terminal:

```shell
npm.cmd test
```

On a freshly extracted source package, first run `npm run setup`, then `npm run dev`. Docker users should follow the README instructions and configure database/bootstrap credentials.

The source package excludes local secrets, uploads, databases, tool caches and old implementation files. Progress reports are explained snapshots of the current rubric and verified records; they are not permanently locked academic grade certificates.

## Startup follow-up, 15 October

A browser screenshot confirmed connection refusal. Logs show Java stopped with exit 1073807364 (0x40010004). Added `START_TRACKSY.cmd` for a normal Windows desktop launch, readiness checks before announcing the URL, an existing-connection check before database startup, and retained launcher/application logs. JavaScript syntax checks passed. The user subsequently confirmed that the Windows launcher printed **Tracksy is ready**, which requires a successful application/database health response. Full HTTP workflows and visual browser checks remain unverified.

Automated observation of the existing browser error page was denied by browser URL policy. Control of the previous terminal process was also rejected because sandbox approval was unavailable; no successful stop or visual result is claimed.
