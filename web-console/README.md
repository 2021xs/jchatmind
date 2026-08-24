# JChatMind Web Console

JChatMind Web Console V1 is a Vite + React frontend for repo selection, conversation switching, Agent chat, and Trace / Audit inspection.

## Stack

- React 19
- Vite / rolldown-vite
- TypeScript
- Ant Design
- Tailwind CSS
- npm with `package-lock.json`

## Configuration

The frontend uses relative backend paths by default:

```env
VITE_JCHATMIND_API_BASE_URL=/api
VITE_JCHATMIND_SSE_BASE_URL=/sse
VITE_JCHATMIND_DEV_PROXY_TARGET=http://127.0.0.1:8080
```

For separated frontend/backend development, set these variables in a local `.env` file. Do not commit local `.env` files.
The dev proxy target is only used by Vite during `npm run dev`.

## Commands

```powershell
npm install
npm run dev
npm run build
npm test
npm run lint
```

`npm test` first compiles the tested TypeScript utilities into the ignored
`target/deterministic-tests` directory and then runs the repository's assertion
scripts. It does not depend on pre-existing build artifacts.

## Backend Integration

Current real backend integrations:

- `GET /api/code-repositories`
- `GET /api/agents`
- `GET /api/chat-sessions`
- `POST /api/chat-sessions`
- session-level `repoId` and `model` selection
- `GET /api/chat-messages/session/{sessionId}`
- `POST /api/chat-messages`
- Agent cancellation
- `GET /api/agent-traces`
- `GET /sse/connect/{chatSessionId}`

The SSE reducer scopes events to the active task/run and reconciles them with
durable messages loaded from the backend. Final Provider chunks are buffered and
validated server-side; after the Final transaction commits, the console receives
TOKEN replay (when enabled) and can always recover the durable answer by reloading
the session.
