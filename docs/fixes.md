# Tracksy fixes

## 21 October 2026: sign-in rejected a populated email

The demo sign-in screen could show `email must contain 5 to 254 characters` even when a valid demo email was visible. The shared form disabled its fields while awaiting submission. Authentication first awaited the CSRF request, then constructed `FormData` from the disabled fields. Browsers omit disabled controls, so the JSON body had no email or password.

`ActionForm` now captures `FormData` synchronously before setting its busy state. All 17 form submission callbacks that read fields use that snapshot, including authentication, project/task changes, evidence uploads and reviews, invitations, logs, and account administration. The original form element is passed separately when a successful comment submission needs to reset it. An immediate submission lock prevents duplicate requests before React renders the disabled state.

Demo accounts remain fictional seeded records in PostgreSQL. They use the same authentication and input validation as other accounts; the validation rules have not been relaxed.

The strict TypeScript compilation and Vite production build completed successfully. No automated tests or interactive login checks were run for this fix.

### Apply the update to a Windows clone

Stop the running server with Ctrl+C in its terminal. In the cloned Tracksy folder, run:

```powershell
git pull --ff-only
npm.cmd --prefix frontend ci
npm.cmd run dev
```

Wait for `Tracksy is ready`, then open `http://127.0.0.1:4000/` and press Ctrl+F5 to load the rebuilt frontend. Keep the server terminal open. `npm.cmd start` reuses an existing application build, so use `npm.cmd run dev` after changing source files.
