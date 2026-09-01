package cn.datacraft.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StaticUiContractTest {
    private static final Path STATIC_ROOT = Path.of("src/main/resources/static");
    private static final Set<String> TYPING_TWO_STATE_PAGES = Set.of("typing-pk.html");
    private static final Set<String> INDEX_IDS = Set.of(
            "requestForm", "statement", "standardCode", "requirements", "caseCount", "cppStandard",
            "analyzeButton", "planView", "progressView", "authView", "passwordView", "recentJobs"
    );

    @Test
    void productPagesExposeSharedShellAndAccessibleControls() throws IOException {
        List<Path> pages = Files.list(STATIC_ROOT)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .sorted()
                .toList();
        assertThat(pages).isNotEmpty();

        for (Path page : pages) {
            String name = page.getFileName().toString();
            Document document = Jsoup.parse(page.toFile(), "UTF-8");
            assertThat(document.title()).as(name + " title").isNotBlank();

            Elements headings = document.select("h1");
            if (TYPING_TWO_STATE_PAGES.contains(name)) {
                // The lobby and an already joined room are two mutually exclusive page states.
                assertThat(headings).as(name + " has lobby/room headings").hasSize(2);
            } else {
                assertThat(headings).as(name + " has one visible page heading").hasSize(1);
            }

            Set<String> ids = new HashSet<>();
            for (Element element : document.select("[id]")) {
                assertThat(ids.add(element.id())).as(name + " duplicate id=" + element.id()).isTrue();
            }

            for (Element button : document.select("button")) {
                assertThat(button.text().trim().isEmpty() && button.attr("aria-label").isBlank())
                        .as(name + " button needs text or aria-label: " + button.outerHtml())
                        .isFalse();
            }
            for (Element control : document.select("input, textarea, select")) {
                if ("hidden".equalsIgnoreCase(control.attr("type"))) continue;
                boolean hasLabel = !control.attr("aria-label").isBlank()
                        || control.parents().stream().anyMatch(parent -> "label".equals(parent.tagName()))
                        || (!control.id().isBlank() && !document.select("label[for=" + control.id() + "]").isEmpty());
                assertThat(hasLabel).as(name + " form control needs label/aria-label: " + control.outerHtml()).isTrue();
            }

            assertThat(document.select("link[href=/styles.css]")).as(name + " shared styles").isNotEmpty();
            assertThat(document.select("script[src=/ui-core.js]")).as(name + " shared ui core").isNotEmpty();
            assertThat(document.html()).doesNotContainIgnoringCase("ui-fixes");
            assertThat(document.select("nav.module-nav a.active")).as(name + " active primary navigation").hasSize(1);
        }
    }

    @Test
    void indexKeepsApplicationContractIds() throws IOException {
        Document document = Jsoup.parse(STATIC_ROOT.resolve("index.html").toFile(), "UTF-8");
        Set<String> ids = document.select("[id]").stream().map(Element::id).collect(Collectors.toSet());
        assertThat(ids).containsAll(INDEX_IDS);

        String appScript = Files.readString(STATIC_ROOT.resolve("app.js"));
        assertThat(appScript)
                .contains("suppressAutoResume: returnedFromAdmin")
                .contains("if (isTerminal(job.status)) localStorage.removeItem('dataforge.activeJob')");

        Document admin = Jsoup.parse(STATIC_ROOT.resolve("admin.html").toFile(), "UTF-8");
        assertThat(admin.select("a.header-shortcut[href='/?from=admin']"))
                .as("admin return link suppresses automatic job recovery")
                .hasSize(1);
    }

    @Test
    void sharedUiKeepsApprovedPaletteAndPublicApi() throws IOException {
        String styles = Files.readString(STATIC_ROOT.resolve("styles.css"));
        assertThat(styles)
                .contains("--df-bg: #F6F8FB")
                .contains("--df-surface: #FFFFFF")
                .contains("--df-brand: #3F6F9F")
                .contains("--df-brand-hover: #2E5E8C")
                .contains("--df-selected: #DCEAF6");

        String uiCore = Files.readString(STATIC_ROOT.resolve("ui-core.js"));
        assertThat(uiCore).contains("window.DataForgeUI = {openDialog, closeDialog, toast, setBusy}");

        try (var files = Files.list(STATIC_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file);
                assertThat(source).as(file.getFileName().toString())
                        .doesNotContainIgnoringCase("#1e593d", "#d7ef72", "#d9f450", "#e4ff63", "ui-fixes");
            }
        }
    }
}
