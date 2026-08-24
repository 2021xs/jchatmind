package com.kama.jchatmind.github;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessBuilderGitCommandRunner implements GitCommandRunner {
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    @Override
    public GitCommandResult run(List<String> arguments, Path workingDirectory, Duration timeout) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            Map<String, String> environment = builder.environment();
            environment.put("GIT_TERMINAL_PROMPT", "0");
            environment.put("GIT_CONFIG_NOSYSTEM", "1");
            environment.put("GIT_CONFIG_GLOBAL", isWindows() ? "NUL" : "/dev/null");
            process = builder.start();
        } catch (IOException e) {
            return new GitCommandResult(-1, "", false, true, false);
        }

        BoundedOutput output = new BoundedOutput(process.getInputStream());
        Thread outputReader = new Thread(output, "github-git-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean timedOut = false;
        boolean interrupted = false;
        try {
            long timeoutMillis = Math.max(1L, timeout.toMillis());
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroy();
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        try {
            outputReader.join(1000);
        } catch (InterruptedException e) {
            interrupted = true;
            Thread.currentThread().interrupt();
        }
        return new GitCommandResult(process.isAlive() ? -1 : process.exitValue(),
                output.value(), timedOut, false, interrupted);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static final class BoundedOutput implements Runnable {
        private final InputStream inputStream;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private BoundedOutput(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            int remaining = MAX_OUTPUT_BYTES;
            try (InputStream input = inputStream) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (remaining > 0) {
                        int copyLength = Math.min(remaining, count);
                        output.write(buffer, 0, copyLength);
                        remaining -= copyLength;
                    }
                }
            } catch (IOException ignored) {
                // Process termination can close the stream while cleanup is in progress.
            }
        }

        private String value() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
