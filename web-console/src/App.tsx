import { useEffect, useState } from "react";
import { Alert, Button, Form, Input, Modal, Select, Spin, message } from "antd";
import { AppHeader } from "./components/AppHeader";
import { ChatPanel } from "./components/ChatPanel";
import { Sidebar } from "./components/Sidebar";
import { TracePanel } from "./components/TracePanel";
import { useChatSend } from "./hooks/useChatSend";
import { useConsoleState } from "./hooks/useConsoleState";
import { useSessionSse } from "./hooks/useSessionSse";
import { importGithubRepository, importLocalRepository } from "./api/client";
import { filterExecutionEvents } from "./utils/executionScope";

function App() {
  const [noticeApi, contextHolder] = message.useMessage();
  const [sessionNameForm] = Form.useForm<{ title: string; repoId: string }>();
  const [localImportForm] = Form.useForm<{ name: string; rootPath: string }>();
  const [githubImportForm] = Form.useForm<{ name?: string; url: string }>();
  const [createSessionModalOpen, setCreateSessionModalOpen] = useState(false);
  const [creatingSession, setCreatingSession] = useState(false);
  const [importMode, setImportMode] = useState<"local" | "github">();
  const [importing, setImporting] = useState(false);
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
    removeAllSessions,
    openDetail,
    refreshCapabilities,
  } = useConsoleState();

  const { draft, setDraft, handleSend, handleStop } = useChatSend({
    selectedSession,
    selectedModel: state.selectedModel,
    selectedSessionId: state.selectedSessionId,
    activeTaskId: state.activeTaskId,
    setState,
    refreshSessionData,
    onError: (text) => noticeApi.error(text),
  });

  useEffect(() => {
    void refreshConsole();
  }, [refreshConsole]);

  useEffect(() => {
    if (state.loadState !== "error") {
      return;
    }
    const retryTimer = window.setTimeout(() => {
      void refreshConsole();
    }, 3_000);
    return () => window.clearTimeout(retryTimer);
  }, [refreshConsole, state.loadState]);

  useEffect(() => {
    void refreshCapabilities(state.selectedRepoId, state.selectedModel);
  }, [refreshCapabilities, state.selectedModel, state.selectedRepoId]);

  useSessionSse({
    sessionId: state.selectedSessionId,
    setState,
    refreshSessionData,
  });

  const selectedTaskId = state.activeTaskId ?? state.selectedTraceId ?? selectedTrace?.id;
  const visibleSseEvents = filterExecutionEvents(
    state.sseEvents,
    selectedTaskId,
    state.selectedSessionId,
  );

  async function handleCreateSession() {
    try {
      const values = await sessionNameForm.validateFields();
      const title = values.title.trim();
      if (!title) {
        return;
      }
      setCreatingSession(true);
      await createSession(title, values.repoId);
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

  async function handleImport() {
    try {
      setImporting(true);
      if (importMode === "github") {
        const values = await githubImportForm.validateFields();
        await importGithubRepository(values.url.trim(), values.name);
      } else {
        const values = await localImportForm.validateFields();
        await importLocalRepository(values.name.trim(), values.rootPath.trim());
      }
      await refreshConsole();
      setImportMode(undefined);
      localImportForm.resetFields();
      githubImportForm.resetFields();
      noticeApi.success("Repository import completed");
    } catch (error) {
      if (isFormValidationError(error)) {
        return;
      }
      noticeApi.error(error instanceof Error ? error.message : String(error));
    } finally {
      setImporting(false);
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
          message="后端接口暂不可用，正在自动重试"
          description={state.error}
          action={
            <Button size="small" onClick={refreshConsole}>
              立即重试
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
              setState((previous) => previous.selectedSessionId
                ? previous
                : { ...previous, selectedRepoId })
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
            onDeleteAllSessions={async () => {
              try {
                await removeAllSessions();
                noticeApi.success("全部会话已删除");
              } catch (error) {
                noticeApi.error(error instanceof Error ? error.message : String(error));
                throw error;
              }
            }}
            onImportLocal={() => setImportMode("local")}
            onImportGithub={() => setImportMode("github")}
          />

          <ChatPanel
            repo={selectedRepo}
            session={selectedSession}
            model={state.selectedModel}
            messages={state.messages}
            traces={state.traces}
            executionTrace={selectedTrace}
            draft={draft}
            sending={state.sending}
            messageStatus={state.messageStatus}
            sessionError={state.sessionError}
            sseStatus={state.sseStatus}
            onDraftChange={setDraft}
            onSend={handleSend}
            onStop={handleStop}
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
            events={visibleSseEvents}
            sessionId={state.selectedSessionId}
            activeTaskId={state.activeTaskId}
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
          <Form.Item
            label="Repository"
            name="repoId"
            initialValue={state.selectedRepoId}
            rules={[{ required: true, message: "请选择 Repository" }]}
          >
            <Select
              placeholder="选择 Repository"
              options={state.repositories.map((repo) => ({
                value: repo.id,
                label: `${repo.name}${repo.status && repo.status !== "READY" ? ` (${repo.status})` : ""}`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={importMode === "github" ? "Import GitHub Repository" : "Import Local Repository"}
        open={Boolean(importMode)}
        okText="Import"
        cancelText="Cancel"
        confirmLoading={importing}
        destroyOnHidden
        onOk={() => void handleImport()}
        onCancel={() => {
          setImportMode(undefined);
          localImportForm.resetFields();
          githubImportForm.resetFields();
        }}
      >
        {importMode === "github" ? (
          <Form form={githubImportForm} layout="vertical" preserve={false}>
            <Form.Item
              label="GitHub URL"
              name="url"
              rules={[{ required: true, whitespace: true, message: "Enter a GitHub URL" }]}
            >
              <Input placeholder="https://github.com/owner/repository" autoFocus />
            </Form.Item>
            <Form.Item label="Display name" name="name">
              <Input placeholder="Optional" />
            </Form.Item>
          </Form>
        ) : (
          <Form form={localImportForm} layout="vertical" preserve={false}>
            <Form.Item
              label="Name"
              name="name"
              rules={[{ required: true, whitespace: true, message: "Enter a repository name" }]}
            >
              <Input autoFocus />
            </Form.Item>
            <Form.Item
              label="Root path"
              name="rootPath"
              rules={[{ required: true, whitespace: true, message: "Enter a local root path" }]}
            >
              <Input placeholder="Allowed by server configuration" />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </main>
  );
}

export default App;

function isFormValidationError(error: unknown): boolean {
  return typeof error === "object" && error !== null && "errorFields" in error;
}
