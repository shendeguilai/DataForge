package cn.datacraft.atcoder;

interface AtcoderProblemTranslator {
    default void requireConfigured() {}

    String translateToChinese(String sourceHtml);

    default String translatePdfTextToChinese(String label, String title, String sourceText) {
        return translateToChinese(sourceText);
    }

    default String translateMarkdownToChinese(String label, String title, String renderedSourceHtml) {
        return translateToChinese(renderedSourceHtml);
    }
}
