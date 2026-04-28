package com.kama.jchatmind.agent;

public final class AgentExecutionContext {
    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private AgentExecutionContext() {
    }

    public static void set(Context context) {
        HOLDER.set(context);
    }

    public static Context get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static void setCurrentStepId(String stepId) {
        Context context = HOLDER.get();
        if (context != null) {
            context.setCurrentStepId(stepId);
        }
    }

    public static void setStepNo(Integer stepNo) {
        Context context = HOLDER.get();
        if (context != null) {
            context.setStepNo(stepNo);
        }
    }

    public static class Context {
        private final String taskId;
        private final String sessionId;
        private String currentStepId;
        private Integer stepNo;

        public Context(String taskId, String sessionId) {
            this.taskId = taskId;
            this.sessionId = sessionId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getCurrentStepId() {
            return currentStepId;
        }

        public void setCurrentStepId(String currentStepId) {
            this.currentStepId = currentStepId;
        }

        public Integer getStepNo() {
            return stepNo;
        }

        public void setStepNo(Integer stepNo) {
            this.stepNo = stepNo;
        }
    }
}
