package cn.datacraft.yuanma;

/** Only predefined, non-sensitive error messages may cross the API boundary. */
public class YuanmaException extends RuntimeException {
    private final String code;
    public YuanmaException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
