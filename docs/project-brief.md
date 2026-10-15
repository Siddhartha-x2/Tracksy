# Tracksy project brief

Tracksy helps a student team and its faculty guide make project progress visible and assess contributions using submitted work. A task connects an assignment to evidence, a faculty decision and the contribution report. Weekly logs, milestones and confidential peer evaluation add context to that record.

## Current architecture

The upgraded application uses **React + TypeScript, Spring Boot with Java 21, and PostgreSQL**. React contains the screens and typed API forms. Spring Security authenticates users, and the Java API checks project membership, roles and lifecycle rules before reading or changing records. PostgreSQL stores the application data and persistent sessions; private evidence files are downloaded through protected API routes. Flyway migrations version the schema.

The older Express/SQLite source and original data are preserved locally as migration references. The new source archive excludes that implementation. Source commits exclude local databases, uploads and credentials.

## Main workflows

- Students propose projects with a selected faculty guide. Work starts after supervision approval. Email-bound invitations and approved join requests control team access.
- Guides and team leaders plan tasks with acceptance criteria, priority, complexity, subtasks and dependencies. Only the assigned student submits evidence, and faculty approval completes the task.
- Evidence revisions and reviews are retained. Changes-requested submissions can be revised; stale reviews/revisions are rejected, and a final rejection cannot be disguised as completion.
- Milestones need faculty approval after the linked tasks have current approved evidence. Weekly logs retain revisions and faculty quality reviews; confidential peer evaluations are limited to one per teammate/month.
- Reports show separate assigned-work completion, weighted rubric scores and shares of verified project work. Every component explains its source counts and denominator. Deadline adherence is a separate metric.
- Students see their own evidence, logs and detailed assessment. Faculty can review the supervised project's records. Team leadership and administrator roles do not grant access to another student's private assessment.
- In-app notifications, overdue/inactivity alerts, scoped activity history, CSV/PDF exports and evidence-derived viva practice support the workflow.

GitHub account ownership can be verified through configured OAuth. Faculty then select verified student identities for bounded public repository reads. Repository scoring requires authenticated signed commits and is initially weighted at zero. No source pushes are performed by the integration. Optional SMTP email requires provider configuration, verified email ownership and user opt-in. Viva prompts use templates over approved evidence; they are not a connected AI assessment service.

## Assessment policy

Task points equal priority base points of 1/3/5 multiplied by complexity from 1 to 5. Initial rubric weights are tasks 40%, evidence quality 20%, weekly consistency 15%, peer evaluation 10%, repository 0%, documentation 10% and milestone completion 5%. Faculty publish versioned changes whose weights total 100. These percentages are a configurable implementation policy, not certified percentages from the inaccessible original SRS.

For example, 6 approved points out of 8 assigned points gives 75% assigned-work completion. If the whole project has 24 approved points, the same student has 25% of verified project work. The rubric score also includes the other published components and is displayed separately. Missing or unavailable components earn zero with an explanation; small-team peer privacy may require adjusting the rubric.

## Start and verification status

With Node.js 24+ installed, run `npm run setup` once on a fresh Windows machine, then `npm run dev` to build and run at `http://localhost:4000`. `npm start` launches a previously built app. `npm test` runs the prepared checks with an isolated test database. The npm entrypoints use `scripts/local.mjs`. Docker Compose is also supplied; follow the credential and profile instructions in the README.

The accessible GPT project review supplied the feature areas used for this upgrade, and the user selected the SRS stack. The older complete SRS messages/attachment still require the user's ChatGPT login, so full SRS compliance is not claimed.

All six migration SQL files and 19 integrity assertions passed in isolated PostgreSQL standalone mode. The final frontend/backend builds passed with 20 Java unit tests. Reports cap weekly denominators at project closure or membership deactivation; repository credit is restricted to the member's assessment period. Exports are labeled progress snapshots, not permanent certified grades. Live HTTP integration and desktop/mobile UI workflows still need confirmation; the execution sandbox blocks the application-to-database socket. Scale targets, recovery, deployment and configured provider integrations need measured checks before claiming production readiness.

See the [implementation guide](implementation.md) for the module mapping, security decisions, scoring formulas, setup details and remaining limits.
