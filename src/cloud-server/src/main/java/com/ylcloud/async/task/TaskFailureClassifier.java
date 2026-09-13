package com.ylcloud.async.task;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class TaskFailureClassifier {
    private final TaskErrorSanitizer sanitizer;

    public TaskFailureClassifier(TaskErrorSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public TaskFailure classify(Throwable error) {
        Throwable root = root(error);
        if(root instanceof StaleTaskException) {
            return new TaskFailure("STALE","RESOURCE_VERSION_STALE",sanitizer.sanitize(root.getMessage()),false,true);
        }
        if(root instanceof FatalTaskException || root instanceof IllegalArgumentException) {
            return new TaskFailure("BUSINESS","FATAL_VALIDATION",sanitizer.sanitize(root.getMessage()),false,false);
        }
        boolean transientFailure = root instanceof RetryableTaskException
                || root instanceof TimeoutException
                || root instanceof SocketTimeoutException
                || root instanceof ConnectException
                || root instanceof ConcurrencyFailureException;
        return new TaskFailure(
                transientFailure ? "TRANSIENT" : "UNKNOWN",
                transientFailure ? "TEMPORARY_FAILURE" : "UNEXPECTED_FAILURE",
                sanitizer.sanitize(root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage()),
                true,
                false
        );
    }

    private Throwable root(Throwable error) {
        Throwable current = error;
        while(current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }
}
