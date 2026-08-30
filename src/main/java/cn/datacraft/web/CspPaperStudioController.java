package cn.datacraft.web;

import cn.datacraft.tools.CspPaperStudioClient;
import cn.datacraft.tools.CspPaperStudioException;
import cn.datacraft.tools.CspPaperStudioProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/api/tools/csp-paper-studio")
public class CspPaperStudioController {
    private static final Set<String> SAMPLE_NAMES = Set.of("2022j", "2023j", "2024j", "2025s");
    private static final Set<String> NUMBERING_MODES = Set.of("auto", "global", "local");

    private final CspPaperStudioClient client;
    private final CspPaperStudioProperties properties;

    public CspPaperStudioController(CspPaperStudioClient client, CspPaperStudioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @GetMapping("/samples/{name}")
    public ResponseEntity<JsonNode> sample(@PathVariable String name) {
        String normalized = name.toLowerCase();
        if (!SAMPLE_NAMES.contains(normalized)) {
            throw new CspPaperStudioException(HttpStatus.NOT_FOUND, "示例不存在");
        }
        return noStore(client.sample(normalized));
    }

    @PostMapping("/analyze")
    public ResponseEntity<JsonNode> analyze(@Valid @RequestBody AnalyzeRequest request) {
        requireMarkdownWithinLimit(request.markdown());
        return noStore(client.analyze(request.markdown()));
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportRequest request) {
        requireMarkdownWithinLimit(request.markdown());
        String numbering = request.numbering() == null || request.numbering().isBlank()
                ? "auto" : request.numbering();
        if (!NUMBERING_MODES.contains(numbering)) {
            throw new CspPaperStudioException(
                    HttpStatus.BAD_REQUEST,
                    "题号模式必须是 auto、global 或 local"
            );
        }
        String filename = request.filename() == null ? "CSP试卷.md" : request.filename();
        CspPaperStudioClient.ExportedWord word = client.export(request.markdown(), filename, numbering);
        String disposition = ContentDisposition.attachment()
                .filename(word.filename(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ))
                .contentLength(word.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(word.content());
    }

    private ResponseEntity<JsonNode> noStore(JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private void requireMarkdownWithinLimit(String markdown) {
        if (markdown.getBytes(StandardCharsets.UTF_8).length > properties.getMaxMarkdownBytes()) {
            throw new CspPaperStudioException(HttpStatus.PAYLOAD_TOO_LARGE, "Markdown 不能超过 2 MiB");
        }
    }

    public record AnalyzeRequest(@NotBlank String markdown) {}

    public record ExportRequest(
            @NotBlank String markdown,
            @Size(max = 200) String filename,
            String numbering
    ) {}
}
