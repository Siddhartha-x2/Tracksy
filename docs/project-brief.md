# Tracksy project brief

## What Tracksy is

Tracksy is intended to help a student team and its faculty guide track project work. Students connect their work to assigned tasks and submit evidence. Faculty reviews submissions. Approved evidence contributes to a project contribution report.

## Requirements found in the Tracksy chat

- Students can log in, open a project, see their assigned tasks, and submit evidence for a task.
- Evidence is linked to the task and enters an **Under Review** state.
- Faculty can review and approve evidence or request changes.
- Approved evidence is used to calculate contribution scores.
- Faculty can review project tasks, logs, evidence, and progress, then generate a report.
- Users must not be able to access another project's protected data.
- Passwords must be stored as secure hashes.

The chat also lists performance, recovery, compatibility, usability, and regression test ideas. Those are test expectations, not agreed technical requirements yet.

## Current implementation

Tracksy now has a React client, an Express API, and a SQLite database. Accounts have student/faculty roles. The browser receives an HTTP-only session cookie, while the server checks identity, project membership, and role before returning or changing project data. Evidence supports notes, links, files, and revision history. Faculty reviews are retained per submission version.

## Suggested first database entities

These are a starting point for discussion, not a finalized schema:

- **User**: identity, email, password hash, and account role.
- **Project**: title, description, term, join code, and faculty owner.
- **ProjectMember**: users associated with each project.
- **Task**: project, assignee, creator, due date, priority, points, and status.
- **Evidence**: task, submitting student, current review status, and revision number.
- **EvidenceVersion**: submitted notes, work link, optional file, and version timestamp.
- **EvidenceReview**: faculty decision, feedback, reviewer, and review time.

The current report score is based on approved task points: priority gives a task 1, 3, or 5 points (Low, Medium, High), and each student's score is their approved assigned points divided by their total assigned points. Confirm this scoring rule with the faculty guide before presenting it as the final academic policy. The database applies project membership checks in server-side queries, not only in the browser.

## Work remaining for a proper multi-user project

1. Confirm the stack and the contribution-point rule with the faculty guide.
2. Verify the app flows locally and refine the interface based on feedback.
3. Add automated checks for authentication, access control, task/evidence workflows, and reporting.
4. Plan database backups and deployment settings for the final demo.
