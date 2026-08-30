package cn.datacraft.tools;

import org.springframework.http.HttpStatus;

public class CspPaperStudioException extends RuntimeException {
    private final HttpStatus status;

    public CspPaperStudioException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public CspPaperStudioException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
