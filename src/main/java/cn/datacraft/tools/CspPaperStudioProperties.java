package cn.datacraft.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "dataforge.csp-paper-studio")
public class CspPaperStudioProperties {
    private URI baseUrl = URI.create("http://127.0.0.1:8765");
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration analyzeTimeout = Duration.ofSeconds(15);
    private Duration exportTimeout = Duration.ofSeconds(180);
    private int maxMarkdownBytes = 2 * 1024 * 1024;
    private int maxWordBytes = 25 * 1024 * 1024;

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getAnalyzeTimeout() { return analyzeTimeout; }
    public void setAnalyzeTimeout(Duration analyzeTimeout) { this.analyzeTimeout = analyzeTimeout; }
    public Duration getExportTimeout() { return exportTimeout; }
    public void setExportTimeout(Duration exportTimeout) { this.exportTimeout = exportTimeout; }
    public int getMaxMarkdownBytes() { return maxMarkdownBytes; }
    public void setMaxMarkdownBytes(int maxMarkdownBytes) { this.maxMarkdownBytes = maxMarkdownBytes; }
    public int getMaxWordBytes() { return maxWordBytes; }
    public void setMaxWordBytes(int maxWordBytes) { this.maxWordBytes = maxWordBytes; }
}
