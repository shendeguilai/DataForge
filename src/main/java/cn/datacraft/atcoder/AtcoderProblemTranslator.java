package cn.datacraft.atcoder;

interface AtcoderProblemTranslator {
    default void requireConfigured() {}

    String translateToChinese(String sourceHtml);
}
