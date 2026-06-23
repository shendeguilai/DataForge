package cn.datacraft.job;

import cn.datacraft.ai.AiConfigService;
import cn.datacraft.ai.AiPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class JobService {
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\b(?:int|signed|int32_t|auto)\\s+main\\s*\\(");
    private final JobRepository repository;
    private final AiPlanner planner;
    private final AiConfigService config;
    private final ObjectMapper mapper;
    private final Path runtimeRoot;
    private final Executor jobExecutor;
    private final int processStackMb;

    public JobService(JobRepository repository, AiPlanner planner, AiConfigService config, ObjectMapper mapper,
                      @Qualifier("jobExecutor") Executor jobExecutor,
                      @Value("${dataforge.runtime-dir:./runtime}") String runtimeDir,
                      @Value("${dataforge.process-stack-mb:256}") int processStackMb) throws IOException {
        this.repository = repository;
        this.planner = planner;
        this.config = config;
        this.mapper = mapper;
        this.jobExecutor = jobExecutor;
        this.processStackMb = Math.max(16, processStackMb);
        this.runtimeRoot = Paths.get(runtimeDir).toAbsolutePath().normalize();
        Files.createDirectories(runtimeRoot);
    }

    public synchronized GenerationJob create(GenerationRequest request, Long userId, Integer userDailyGenerationLimit) {
        enforceDailyLimit(userId, userDailyGenerationLimit);
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setRequest(request);
        repository.save(job);
        jobExecutor.execute(() -> analyze(job.getId()));
        return job;
    }

    public GenerationJob retryPlan(UUID id) {
        GenerationJob job = require(id);
        if (job.getStatus() != JobStatus.FAILED) {
            throw new IllegalStateException("只有失败的任务才能重新生成方案");
        }
        DataPlan previousPlan = job.getPlan();
        String previousError = job.getError();
        job.clearError();
        job.setArtifact(null);
        job.update(JobStatus.CHECKING_CODE, 4, "正在根据失败原因重新生成方案");
        repository.save(job);
        jobExecutor.execute(() -> analyze(id, previousPlan, previousError));
        return job;
    }

    private void enforceDailyLimit(Long userId, Integer userDailyGenerationLimit) {
        int limit = userDailyGenerationLimit != null && userDailyGenerationLimit > 0 ? userDailyGenerationLimit : config.dailyGenerationLimit();
        Instant todayStart = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        long used = repository.countCreatedSince(userId, todayStart);
        if (used >= limit) {
            throw new IllegalStateException("今日生成次数已达上限（" + limit + " 次），请明天再试或联系管理员调整额度");
        }
    }

    public void analyze(UUID id) {
        analyze(id, null, null);
    }

    private void analyze(UUID id, DataPlan previousPlan, String previousError) {
        GenerationJob job = require(id);
        try {
            job.update(JobStatus.CHECKING_CODE, 4, "正在检查并编译标准程序");
            repository.save(job);
            validateStandardProgram(job);
            job.update(JobStatus.ANALYZING, 10, previousError == null ? "AI 正在理解题面和数据要求" : "AI 正在根据失败原因重新生成方案");
            repository.save(job);
            DataPlan plan = planner.createPlan(job.getRequest(), previousPlan, previousError);
            job.setPlan(plan);
            repository.save(job);
            job.update(JobStatus.COMPILING, 17, "正在检查 AI 数据生成器");
            repository.save(job);
            validateGenerator(job, plan);
            job.update(JobStatus.WAITING_CONFIRMATION, 20, "生成方案已准备，请确认");
            repository.save(job);
        } catch (Exception ex) {
            job.fail(cleanError(ex));
            repository.save(job);
        }
    }

    public GenerationJob confirm(UUID id) {
        GenerationJob job = require(id);
        if (job.getStatus() != JobStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("当前任务不能开始生成");
        }
        job.update(JobStatus.QUEUED, 22, "任务已进入生成队列");
        repository.save(job);
        jobExecutor.execute(() -> generate(id));
        return job;
    }

    public void generate(UUID id) {
        GenerationJob job = require(id);
        Path dir = runtimeRoot.resolve(id.toString());
        try {
            Files.createDirectories(dir.resolve("data"));
            job.update(JobStatus.COMPILING, 28, "正在编译数据生成器和标准程序");
            repository.save(job);
            Path generatorSource = dir.resolve("generator.cpp");
            Path standardSource = dir.resolve("standard.cpp");
            Files.write(generatorSource, job.getPlan().getGeneratorCode().getBytes(StandardCharsets.UTF_8));
            Files.write(standardSource, job.getRequest().getStandardCode().getBytes(StandardCharsets.UTF_8));
            String suffix = isWindows() ? ".exe" : "";
            Path generatorExe = dir.resolve("generator" + suffix);
            Path standardExe = dir.resolve("standard" + suffix);
            try {
                compile(generatorSource, generatorExe, job.getRequest().getCppStandard(), dir);
            } catch (Exception ex) {
                throw new IOException("AI 数据生成器编译失败：\n" + cleanError(ex), ex);
            }
            try {
                compile(standardSource, standardExe, job.getRequest().getCppStandard(), dir);
            } catch (Exception ex) {
                throw new IOException("标准程序编译失败：\n" + cleanError(ex), ex);
            }

            int count = job.getRequest().getCaseCount();
            List<Map<String, Object>> cases = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                int progress = 32 + (int) (55.0 * i / count);
                job.update(JobStatus.GENERATING, progress, "正在生成并计算第 " + i + "/" + count + " 组数据");
                repository.save(job);
                long seed = createSeed(job.getId(), i);
                Path input = dir.resolve("data").resolve(i + ".in");
                Path output = dir.resolve("data").resolve(i + ".out");
                try {
                    run(Arrays.asList(generatorExe.toString(), String.valueOf(seed), String.valueOf(i)), dir, null, input, Duration.ofSeconds(12));
                } catch (Exception ex) {
                    throw new IOException("AI 数据生成器运行失败（第 " + i + " 组，seed=" + seed + "）：\n" + cleanError(ex), ex);
                }
                if (Files.size(input) == 0 || Files.size(input) > 64L * 1024 * 1024) throw new IOException("第 " + i + " 组输入为空或超过 64MB 限制");
                Instant started = Instant.now();
                try {
                    run(Collections.singletonList(standardExe.toString()), dir, input, output, Duration.ofSeconds(10));
                } catch (Exception ex) {
                    throw new IOException("标准程序运行失败（第 " + i + " 组）：\n" + cleanError(ex), ex);
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("id", i); meta.put("seed", seed); meta.put("inputBytes", Files.size(input));
                meta.put("outputBytes", Files.size(output)); meta.put("runtimeMs", Duration.between(started, Instant.now()).toMillis());
                cases.add(meta);
            }
            job.update(JobStatus.PACKAGING, 92, "正在生成报告并打包 ZIP");
            repository.save(job);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("jobId", id); manifest.put("createdAt", Instant.now()); manifest.put("caseCount", count);
            manifest.put("aiGenerated", job.getPlan().isAiGenerated()); manifest.put("cases", cases);
            Files.write(dir.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            Files.write(dir.resolve("problem.md"), job.getRequest().getStatement().getBytes(StandardCharsets.UTF_8));
            Path zip = runtimeRoot.resolve("dataforge-" + id + ".zip");
            zipDirectory(dir, zip);
            job.setArtifact(zip);
            job.update(JobStatus.COMPLETED, 100, "数据包生成完成");
            repository.save(job);
        } catch (Exception ex) {
            job.fail(cleanError(ex));
            repository.save(job);
        }
    }

    private void compile(Path source, Path output, String standard, Path dir) throws Exception {
        String safeStandard = Arrays.asList("c++14", "c++17", "c++20").contains(standard) ? standard : "c++17";
        List<String> command = new ArrayList<>(Arrays.asList("g++", "-std=" + safeStandard, "-O2", "-pipe", source.toString(), "-o", output.toString()));
        if (isWindows()) {
            long stackBytes = Math.min((long) processStackMb, 1024L) * 1024L * 1024L;
            command.add("-Wl,--stack," + stackBytes);
        }
        run(command, dir, null, null, Duration.ofSeconds(30));
    }

    private void validateStandardProgram(GenerationJob job) throws Exception {
        String code = job.getRequest().getStandardCode();
        if (!hasMainFunction(code)) {
            throw new IllegalArgumentException("标准程序中没有找到 main 函数。请确认这里只粘贴 C++ 标准解，不要粘贴 Markdown 题面或 ```cpp 代码围栏。");
        }
        Path dir = runtimeRoot.resolve(job.getId().toString());
        Files.createDirectories(dir);
        Path source = dir.resolve("standard.cpp");
        Path output = dir.resolve("standard-check" + (isWindows() ? ".exe" : ""));
        Files.write(source, code.getBytes(StandardCharsets.UTF_8));
        try {
            compile(source, output, job.getRequest().getCppStandard(), dir);
        } catch (Exception ex) {
            throw new IOException("标准程序编译失败：\n" + cleanError(ex), ex);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void validateGenerator(GenerationJob job, DataPlan plan) throws Exception {
        if (plan == null || !hasMainFunction(plan.getGeneratorCode())) {
            throw new IOException("AI 返回的数据生成器不完整：没有找到 main 函数。请重新分析。");
        }
        Path dir = runtimeRoot.resolve(job.getId().toString());
        Files.createDirectories(dir);
        Path source = dir.resolve("generator.cpp");
        Path output = dir.resolve("generator-check" + (isWindows() ? ".exe" : ""));
        Files.write(source, plan.getGeneratorCode().getBytes(StandardCharsets.UTF_8));
        try {
            compile(source, output, job.getRequest().getCppStandard(), dir);
        } catch (Exception ex) {
            throw new IOException("AI 数据生成器编译失败：\n" + cleanError(ex), ex);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void run(List<String> command, Path workDir, Path input, Path output, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(withProcessStackLimit(command)).directory(workDir.toFile());
        if (input != null) builder.redirectInput(input.toFile());
        if (output != null) builder.redirectOutput(output.toFile()); else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Path error = Files.createTempFile(workDir, "process-", ".err");
        builder.redirectError(error.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("程序运行超时（" + timeout.getSeconds() + " 秒）");
        }
        if (process.exitValue() != 0) {
            String detail = new String(Files.readAllBytes(error), StandardCharsets.UTF_8);
            if (detail.length() > 2000) detail = detail.substring(0, 2000);
            throw new IOException("程序执行失败，退出码 " + process.exitValue() + exitCodeHint(process.exitValue()) + "：" + detail);
        }
        Files.deleteIfExists(error);
    }

    private List<String> withProcessStackLimit(List<String> command) {
        if (isWindows()) return command;
        long stackKb = Math.min((long) processStackMb, 1024L) * 1024L;
        List<String> wrapped = new ArrayList<>();
        wrapped.add("/bin/sh");
        wrapped.add("-c");
        wrapped.add("ulimit -s " + stackKb + " 2>/dev/null || true; exec \"$@\"");
        wrapped.add("dataforge-process");
        wrapped.addAll(command);
        return wrapped;
    }

    private String exitCodeHint(int code) {
        if (code == -1073741571) return "（Windows 栈溢出，常见原因是深递归或函数局部超大数组）";
        if (code == -1073741819) return "（Windows 访问冲突，常见原因是数组越界、空指针或非法内存访问）";
        if (code == -1073741676) return "（Windows 整数除零或非法算术运算）";
        if (code == -1073740791) return "（Windows fast-fail，常见原因是运行库检测到严重内存错误）";
        return "";
    }

    private void zipDirectory(Path source, Path zip) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
             java.util.stream.Stream<Path> paths = Files.walk(source)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String name = source.relativize(path).toString().replace('\\', '/');
                    if (name.endsWith(".exe") || name.endsWith(".err")) return;
                    out.putNextEntry(new ZipEntry(name));
                    Files.copy(path, out);
                    out.closeEntry();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }

    public GenerationJob require(UUID id) { return repository.find(id).orElseThrow(() -> new NoSuchElementException("任务不存在")); }
    public GenerationJob requireOwned(UUID id, Long userId) {
        GenerationJob job = require(id);
        if (!Objects.equals(job.getUserId(), userId)) throw new NoSuchElementException("任务不存在");
        return job;
    }
    public List<GenerationJob> recent(Long userId) { return repository.recent(userId); }
    public List<GenerationJob> recentAll() { return repository.recentAll(); }
    static boolean hasMainFunction(String code) {
        return code != null && !code.contains("```") && MAIN_FUNCTION.matcher(code).find();
    }
    static long createSeed(UUID jobId, int caseNumber) {
        long mixed = jobId.getMostSignificantBits()
                ^ jobId.getLeastSignificantBits()
                ^ caseNumber * 1_000_003L;
        // Keep the seed compatible even when an AI-generated program uses stoi by mistake.
        return Math.floorMod(mixed, (long) Integer.MAX_VALUE - 1L) + 1L;
    }
    private boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    private String cleanError(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message;
    }
}
