package cn.datacraft.yuanma;

public interface YuanmaGateway {
    record Identity(long userId, String userName, String nickName) {}
    Identity identity(String token);
}
