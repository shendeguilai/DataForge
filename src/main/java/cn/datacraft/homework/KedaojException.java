package cn.datacraft.homework;

public class KedaojException extends RuntimeException {
    private final String code;
    public KedaojException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
