package cn.datacraft.job;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GenerationTaskQueue {
    static final ThreadLocal<String> CURRENT_TOKEN=new ThreadLocal<>();
    private final GenerationTaskRepository records;
    private final ObjectProvider<JobService> jobs;
    private final Executor executor;
    private final TransactionTemplate tx;
    private final Map<String,Thread> running=new ConcurrentHashMap<>();
    private final Map<String,String> leases=new ConcurrentHashMap<>();
    private final Set<String> submitted=ConcurrentHashMap.newKeySet();
    private final boolean enabled;
    public GenerationTaskQueue(GenerationTaskRepository records,ObjectProvider<JobService> jobs,
            @Qualifier("jobExecutor") Executor executor,PlatformTransactionManager manager,
            @Value("${dataforge.generation.scheduling-enabled:true}")boolean enabled) {
        this.records=records;this.jobs=jobs;this.executor=executor;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
    }
    public void enqueue(UUID id,String phase) {
        tx.executeWithoutResult(s->{var row=records.lock(id.toString()).orElseGet(GenerationTask::new);
            row.id=id.toString();row.phase=phase;row.state="QUEUED";row.token=null;row.leaseUntil=null;row.updatedAt=Instant.now();records.saveAndFlush(row);});
    }
    public void cancel(UUID id) {
        tx.executeWithoutResult(s->{var row=records.lock(id.toString());if(row.isPresent()){var r=row.get();r.state="CANCELLED";r.token=null;r.leaseUntil=null;records.saveAndFlush(r);}});
        Thread thread=running.get(id.toString());if(thread!=null)thread.interrupt();
    }
    @Scheduled(fixedDelay=2000) public void dispatch() {
        if(!enabled)return;
        for(var row:records.findByStateInOrderByUpdatedAtAsc(List.of("QUEUED","RUNNING"),PageRequest.of(0,20))) {
            if(row.leaseUntil!=null&&row.leaseUntil.isAfter(Instant.now()) || !submitted.add(row.id))continue;
            try{executor.execute(()->{try{execute(row.id);}finally{submitted.remove(row.id);}});}catch(RejectedExecutionException e){submitted.remove(row.id);break;}
        }
    }
    private void execute(String id) {
        String token=UUID.randomUUID().toString();
        String phase=tx.execute(s->{var r=records.lock(id).orElseThrow();
            if(!"QUEUED".equals(r.state)&&!("RUNNING".equals(r.state)&&r.leaseUntil!=null&&!r.leaseUntil.isAfter(Instant.now())))return null;
            r.state="RUNNING";r.token=token;r.leaseUntil=Instant.now().plusSeconds(120);records.saveAndFlush(r);return r.phase;});
        if(phase==null)return;
        running.put(id,Thread.currentThread());leases.put(id,token);
        CURRENT_TOKEN.set(token);
        try {
            var job=jobs.getObject().require(UUID.fromString(id));
            if(Set.of(JobStatus.COMPLETED,JobStatus.FAILED,JobStatus.CANCELLED).contains(job.getStatus())
                    ||"ANALYZE".equals(phase)&&job.getStatus()==JobStatus.WAITING_CONFIRMATION)return;
            if("GENERATE".equals(phase))jobs.getObject().generate(UUID.fromString(id));else jobs.getObject().analyze(UUID.fromString(id));
        } catch(GenerationCancelledException | CancellationException ignored) {
        } finally {
            running.remove(id);leases.remove(id);CURRENT_TOKEN.remove();Thread.interrupted();
            tx.executeWithoutResult(s->{var r=records.lock(id).orElseThrow();if(Objects.equals(r.token,token)){
                var status=jobs.getObject().require(UUID.fromString(id)).getStatus();
                r.state=Set.of(JobStatus.COMPLETED,JobStatus.FAILED,JobStatus.CANCELLED,JobStatus.WAITING_CONFIRMATION).contains(status)?"DONE":"QUEUED";
                r.token=null;r.leaseUntil=null;r.updatedAt=Instant.now();records.saveAndFlush(r);}});
        }
    }
    @Scheduled(fixedDelay=20000) public void heartbeat() {
        leases.forEach((id,token)->tx.executeWithoutResult(s->{var r=records.lock(id).orElseThrow();
            if(Objects.equals(r.token,token)){r.leaseUntil=Instant.now().plusSeconds(120);records.saveAndFlush(r);}
            else {Thread t=running.get(id);if(t!=null)t.interrupt();}}));
    }
}
