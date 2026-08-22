package cn.datacraft.atcoder;

interface AtcoderProblemSourceGateway {
    String fetchTaskPage(String contestId, String taskId);
}
