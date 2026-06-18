import { useEffect } from "react";
import { Alert, Button, Spin, message } from "antd";
import { AppHeader } from "./components/AppHeader";
import { ChatPanel } from "./components/ChatPanel";
import { Sidebar } from "./components/Sidebar";
import { TracePanel } from "./components/TracePanel";
import { useChatSend } from "./hooks/useChatSend";
import { useConsoleState } from "./hooks/useConsoleState";
import { useSessionSse } from "./hooks/useSessionSse";

function App() {
  const [noticeApi, contextHolder] = message.useMessage();
  const {
    state,
    setState,
    selectedRepo,
    sortedSessions,
    selectedSession,
    selectedAgent,
    selectedTrace,
    visibleToolCalls,
    refreshConsole,
    refreshSessionData,
    createSession,
    selectSession,
    openDetail,
  } = useConsoleState();

  const { draft, setDraft, handleSend } = useChatSend({
    selectedSession,
    selectedAgentId: state.selectedAgentId,
    selectedRepoId: state.selectedRepoId,
    selectedSessionId: state.selectedSessionId,
    setState,
    refreshSessionData,
    onError: (text) => noticeApi.error(text),
  });

  useEffect(() => {
    void refreshConsole();
  }, [refreshConsole]);

  useSessionSse({
    sessionId: state.selectedSessionId,
    setState,
    refreshSessionData,
  });

  async function handleCreateSession() {
    try {
      await createSession();
    } catch (error) {
      noticeApi.error(error instanceof Error ? error.message : String(error));
    }
  }

  return (
    <main className="console-shell">
      {contextHolder}
      <AppHeader
        repo={selectedRepo}
        agent={selectedAgent}
        session={selectedSession}
        sseStatus={state.sseStatus}
        detailOpen={state.detailOpen}
        onRefresh={refreshConsole}
        onToggleDetail={() =>
          setState((previous) => ({
            ...previous,
            detailOpen: !previous.detailOpen,
          }))
        }
      />

      {state.error ? (
        <Alert
          className="top-alert"
          type="warning"
          showIcon
          closable
          message="后端接口暂不可用"
          description={state.error}
          action={
            <Button size="small" onClick={refreshConsole}>
              重试
            </Button>
          }
          onClose={() => setState((previous) => ({ ...previous, error: undefined }))}
        />
      ) : null}

      <Spin spinning={state.loadState === "loading"}>
        <section className={`console-layout ${state.detailOpen ? "" : "detail-collapsed"}`}>
          <Sidebar
            repositories={state.repositories}
            sessions={sortedSessions}
            agents={state.agents}
            selectedRepoId={state.selectedRepoId}
            selectedSessionId={state.selectedSessionId}
            selectedAgentId={state.selectedAgentId}
            onSelectRepo={(selectedRepoId) =>
              setState((previous) => ({ ...previous, selectedRepoId }))
            }
            onSelectAgent={(selectedAgentId) =>
              setState((previous) => ({ ...previous, selectedAgentId }))
            }
            onSelectSession={selectSession}
            onCreateSession={handleCreateSession}
          />

          <ChatPanel
            repo={selectedRepo}
            session={selectedSession}
            agent={selectedAgent}
            messages={state.messages}
            traces={state.traces}
            draft={draft}
            sending={state.sending}
            messageStatus={state.messageStatus}
            sessionError={state.sessionError}
            sseStatus={state.sseStatus}
            onDraftChange={setDraft}
            onSend={handleSend}
            onOpenTrace={(traceId) => openDetail("trace", traceId)}
            onOpenTools={() => openDetail("tools")}
            onRetrySession={() =>
              state.selectedSessionId
                ? void refreshSessionData(state.selectedSessionId)
                : undefined
            }
          />

          <TracePanel
            open={state.detailOpen}
            mode={state.detailMode}
            traces={state.traces}
            selectedTrace={selectedTrace}
            toolCalls={visibleToolCalls}
            events={state.sseEvents}
            sessionId={state.selectedSessionId}
            onModeChange={(detailMode) =>
              setState((previous) => ({ ...previous, detailMode }))
            }
            onSelectTrace={(selectedTraceId) =>
              setState((previous) => ({ ...previous, selectedTraceId }))
            }
            onClose={() => setState((previous) => ({ ...previous, detailOpen: false }))}
          />
        </section>
      </Spin>
    </main>
  );
}

export default App;
