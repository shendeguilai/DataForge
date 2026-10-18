package cn.datacraft.homework;

import cn.datacraft.homework.HomeworkTypes.*;
import java.util.List;

public interface KedaojGateway {
    record Identity(long uid, String uname) {}
    Identity identity(String owner, String sid, String domain);
    Identity user(String owner, String sid, String domain, long uid);
    Problem problem(String owner, String sid, String domain, String pid);
    List<Submission> records(String owner, String sid, String domain, long uid, long docId, Runnable heartbeat);
    default RecordFetch recentRecords(String owner, String sid, String domain, long uid, long docId,
                                      String knownRid, Runnable heartbeat) {
        return new RecordFetch(records(owner, sid, domain, uid, docId, heartbeat), true);
    }
}
