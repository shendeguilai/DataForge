package cn.datacraft.atcoder;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtcoderContestPdfParserTest {
    private final AtcoderContestPdfParser parser = new AtcoderContestPdfParser();

    @Test
    void splitsContestPdfByTaskHeaderAndKeepsPageRanges() throws Exception {
        byte[] pdf = pdf(
                "A - Warm Up\nProblem Statement\nPrint A.\nInput\nA\nOutput\nThe answer.\nSample Input 1\n1\nSample Output 1\n1",
                "Explanation\nThe sample answer is one.",
                "B - Strings\nProblem Statement\nPrint B.\nInput\nB\nOutput\nThe answer.\nSample Input 1\nX\nSample Output 1\nX"
        );

        AtcoderContestPdfParser.ParsedContestPdf parsed = parser.parse(pdf, "contest.pdf");

        assertThat(parsed.pageCount()).isEqualTo(3);
        assertThat(parsed.problems()).hasSize(2);
        assertThat(parsed.problems().get(0).label()).isEqualTo("A");
        assertThat(parsed.problems().get(0).title()).isEqualTo("Warm Up");
        assertThat(parsed.problems().get(0).startPage()).isEqualTo(1);
        assertThat(parsed.problems().get(0).endPage()).isEqualTo(2);
        assertThat(parsed.problems().get(0).sourceText()).contains("Problem Statement", "sample answer");
        assertThat(parsed.problems().get(0).samplePairCount()).isEqualTo(1);
        assertThat(parsed.problems().get(1).label()).isEqualTo("B");
    }

    @Test
    void rejectsImageOnlyOrUnrecognizedPdf() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThatThrownBy(() -> parser.parse(pdf, "scan.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可提取文字");
    }

    private static byte[] pdf(String... pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String value : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.newLineAtOffset(48, 750);
                    for (String line : value.split("\\n", -1)) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
