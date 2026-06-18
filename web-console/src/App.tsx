import { useEffect, useState } from "react";
import { Alert, Button, Form, Input, Modal, Spin, message } from "antd";
import { AppHeader } from "./components/AppHeader";
import { ChatPanel } from "./components/ChatPanel";
import { Sidebar } from "./components/Sidebar";
import { TracePanel } from "./components/TracePanel";
import { useChatSend } from "./hooks/useChatSend";
import { useConsoleState } from "./hooks/useConsoleState";
import { useSessionSse } from "./hooks/useSessionSse";

function App() {
  const [noticeApi, contextHolder] = message.useMessage();
  const [sessionNameForm] = Form.useForm<{ title: string }>();
  const [createSessionModalOpen, setCreateSessionModalOpen] = useState(false);
  const [creatingSession, setCreatingSession] = useState(false);
  const {
    state,
    setState,
    selectedRepo,
    sortedSessions,
    selectedSession,
    selectedTrace,
    visibleToolCalls,
    refreshConsole,
    refreshSessionData,
    createSession,
    selectSession,
    removeRepository,
    removeSession,
    openDetail,
    refreshCapabilities,
  } = useConsoleState();

  const { draft, setDraft, handleSend } = useChatSend({
    selectedSession,
    selectedModel: state.selectedModel,
    selectedRepoId: state.selectedRepoId,
    selectedSessionId: state.selectedSessionId,
    setState,
    refreshSessionData,
    onError: (text) => noticeApi.error(text),
  });

  useEffect(() => {
    void refreshConsole();
  }, [refreshConsole]);

  useEffect(() => {
    void refreshCapabilities(state.selectedRepoId, state.selectedModel);
  }, [refreshCapabilities, state.selectedModel, state.selectedRepoId]);

  useSessionSse({
    sessionId: state.selectedSessionId,
    setState,
    refreshSessionData,
  });

  async function handleCreateSession() {
    try {
      const values = await sessionNameForm.validateFields();
      const title = values.title.trim();
      if (!title) {
        return;
      }
      setCreatingSession(true);
      await createSession(title);
      setCreateSessionModalOpen(false);
      sessionNameForm.resetFields();
    } catch (error) {
      if (isFormValidationError(error)) {
        return;
      }
      noticeApi.error(error instanceof Error ? error.message : String(error));
    } finally {
      setCreatingSession(false);
    }
  }

  return (
    <main className="console-shell">
      {contextHolder}
      <AppHeader
        model={state.selectedModel}
        capabilities={state.capabilities}
        capabilityLoading={state.capabilityLoading}
        capabilityError={state.capabilityError}
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
            selectedModel={state.selectedModel}
            onSelectRepo={(selectedRepoId) =>
              setState((previous) => ({ ...previous, selectedRepoId }))
            }
            onSelectModel={(selectedModel) =>
              setState((previous) => ({ ...previous, selectedModel }))
            }
            onSelectSession={selectSession}
            onCreateSession={() => setCreateSessionModalOpen(true)}
            creatingSession={creatingSession}
            onDeleteRepo={async (repoId) => {
              try {
                await removeRepository(repoId);
                noticeApi.success("仓库索引已删除");
              } catch (error) {
                noticeApi.error(error instanceof Error ? error.message : String(error));
                throw error;
              }
            }}
            onDeleteSession={async (sessionId) => {
              try {
                await removeSession(sessionId);
                noticeApi.success("会话已删除");
              } catch (error) {
                noticeApi.error(error instanceof Error ? error.message : String(error));
                throw error;
              }
            }}
          />

          <ChatPanel
            repo={selectedRepo}
            session={selectedSession}
            model={state.selectedModel}
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

      <Modal
        title="新建会话"
        open={createSessionModalOpen}
        okText="创建"
        cancelText="取消"
        confirmLoading={creatingSession}
        destroyOnHidden
        onOk={() => void handleCreateSession()}
        onCancel={() => {
          setCreateSessionModalOpen(false);
          sessionNameForm.resetFields();
        }}
      >
        <Form form={sessionNameForm} layout="vertical" preserve={false}>
          <Form.Item
            label="会话名称"
            name="title"
            rules={[
              { required: true, whitespace: true, message: "请输入会话名称" },
              { max: 80, message: "会话名称最多 80 个字符" },
            ]}
          >
            <Input
              autoFocus
              placeholder="例如：秒杀链路排查"
              maxLength={80}
              onPressEnter={() => void handleCreateSession()}
            />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  );
}

export default App;

function isFormValidationError(error: unknown): boolean {
  return typeof error === "object" && error !== null && "errorFields" in error;
}
