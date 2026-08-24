package com.kama.jchatmind.github;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface GitCommandRunner {
    GitCommandResult run(List<String> arguments, Path workingDirectory, Duration timeout);

    record GitCommandResult(int exitCode, String output, boolean timedOut,
                            boolean executableUnavailable, boolean interrupted) {
        public boolean succeeded() {
            return !timedOut && !executableUnavailable && !interrupted && exitCode == 0;
        }
    }
}
