package cn.datacraft.web;

import cn.datacraft.tools.CspPaperStudioException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(CspPaperStudioException.class)
    public ResponseEntity<Map<String, String>> cspPaperStudio(CspPaperStudioException ex) {
        return error(ex.getStatus(), ex.getMessage());
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(Exception ex) { return error(HttpStatus.NOT_FOUND, ex.getMessage()); }
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception ex) { return error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> unauthorized(Exception ex) { return error(HttpStatus.UNAUTHORIZED, "用户名或密码错误"); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(Exception ex) { return error(HttpStatus.FORBIDDEN, ex.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(Exception ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件不能超过 25MB");
    }
    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
