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
npm run lint
```

## Backend Integration

Current real backend integrations:

- `GET /api/code-repositories`
- `GET /api/agents`
- `GET /api/chat-sessions`
- `POST /api/chat-sessions`
- `GET /api/chat-messages/session/{sessionId}`
- `POST /api/chat-messages`
- `GET /api/agent-traces`
- `GET /sse/connect/{chatSessionId}`

Conversation to repo binding is only represented in the UI for now because the existing backend `chat_session` model does not expose a `repoId` field.
