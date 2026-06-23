package cn.datacraft.job;

public enum JobStatus {
    CHECKING_CODE,
    ANALYZING,
    WAITING_CONFIRMATION,
    QUEUED,
    COMPILING,
    GENERATING,
    PACKAGING,
    COMPLETED,
    FAILED
}
