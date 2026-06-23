package cn.datacraft.ai;

import cn.datacraft.job.DataPlan;
import cn.datacraft.job.GenerationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AiPlanner {
    private final ObjectMapper mapper;
    private final RestTemplate rest = new RestTemplate();
    private final AiConfigService config;

    public AiPlanner(ObjectMapper mapper, AiConfigService config) { this.mapper = mapper; this.config = config; }

    public DataPlan createPlan(GenerationRequest request) {
        return createPlan(request, null, null);
    }

    public DataPlan createPlan(GenerationRequest request, DataPlan previousPlan, String previousError) {
        AiConfigService.Settings settings = config.current();
        if (settings.baseUrl == null || settings.baseUrl.trim().isEmpty() || settings.apiKey == null || settings.apiKey.trim().isEmpty()) {
            return fallback(request);
        }
        try {
            String prompt = buildPrompt(request, previousPlan, previousError);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", settings.model);
            body.put("temperature", 0.15);
            body.put("messages", Arrays.asList(
                    new LinkedHashMap<String, String>() {{ put("role", "system"); put("content", "你是严谨且有对抗意识的算法竞赛测试数据工程师。必须先完整推导题意约束、可能的正解复杂度、暴力/错误/侥幸算法及其会被哪些数据击穿，然后再设计测试点和生成器。只输出严格 JSON，禁止 Markdown 和额外解释；除 generatorCode 中的 C++ 源码外，所有面向用户展示的文字必须使用简体中文；C++ 代码必须作为合法 JSON 字符串。"); }},
                    new LinkedHashMap<String, String>() {{ put("role", "user"); put("content", prompt); }}));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(settings.apiKey);
            String url = settings.baseUrl.replaceAll("/$", "") + "/chat/completions";
            ResponseEntity<JsonNode> response = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
            String content = response.getBody().path("choices").path(0).path("message").path("content").asText();
            content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            DataPlan plan = mapper.readValue(content, DataPlan.class);
            if (plan.getGeneratorCode() == null || plan.getGeneratorCode().trim().isEmpty()) throw new IllegalStateException("AI 未返回生成器代码");
            plan.setAiGenerated(true);
            return plan;
        } catch (Exception ex) {
            throw new IllegalStateException("AI 方案生成失败，已停止任务以避免产生与题意不符的数据：" + readableError(ex), ex);
        }
    }

    private String buildPrompt(GenerationRequest request, DataPlan previousPlan, String previousError) {
        List<String> lines = new ArrayList<>(Arrays.asList(
                "请根据下方题面、标准程序和出题要求，设计完整测试数据方案，并生成一个确定性的 " + request.getCppStandard() + " 输入生成器。",
                "",
                "【返回格式】",
                "只返回一个 JSON 对象，字段严格为：",
                "- summary: string，概括覆盖策略以及主要针对的错误算法；",
                "- estimatedSize: string，合理估计所有输入文件的总大小；",
                "- groups: array，每项仅包含 range(string) 和 purpose(string)，测试点编号必须完整覆盖且不重复；",
                "- generatorCode: string，完整可编译的 C++17 源码。",
                "- 语言要求：JSON 字段名必须保持英文；summary、estimatedSize、groups[].range、groups[].purpose 等字段值必须使用简体中文，不要返回英文说明；generatorCode 内的 C++ 关键字、变量名、注释不受此限制，但建议注释也使用中文或不写注释。",
                "",
                "【生成前的对抗性分析——必须先内部完成】",
                "- 在设计任何测试点之前，必须先根据题面和标准程序，推断本题可能的正解复杂度、关键不变量、关键边界和输入结构；",
                "- 必须系统思考哪些错误代码可能侥幸通过弱数据，包括但不限于：纯暴力、半暴力/分块不完整、只处理随机数据的做法、贪心误判、排序后丢失原结构、去重错误、边界偏一、初始化遗漏、32 位溢出、递归深度爆栈、只考虑连通/无重边/无自环/无重复值等题面未保证的假设；",
                "- 对每一种高风险错误思路，要思考它最怕什么输入：最大规模、极小规模、退化链、星形、完全重合、完全不同、大量重复、值域极端、查询集中打同一区域、答案刚好跨边界、构造性反例、卡复杂度的顺序、卡缓存/常数的密集数据等；",
                "- 测试点必须由这些错误思路反推出来：不是先随机生成再解释，而是先确定要淘汰哪些错误解法，再设计对应数据；",
                "- 对于可能通过小数据的暴力，必须安排至少一组达到或接近 100% 上限的压力数据；对于可能通过随机数据的错误贪心/错误假设，必须安排构造性反例；对于可能只在边界失败的代码，必须安排 min、max、min+1、max-1、空/单元素/双元素（若合法）等边界；",
                "- 如果题面有百分比分档，小分档也要有能区分错误解法的小型反例；大分档再安排真正卡复杂度和卡极端结构的数据；不能把所有有价值的 hack 数据都放到 100% 档而让前面分档空洞；",
                "- summary 必须简洁写明本次主要针对哪些暴力/错误/侥幸算法；groups 的每个 purpose 必须写清楚该编号范围想卡掉的错误类型和采用的关键结构，例如“卡 O(nq) 暴力：n,q 接近上限且查询集中覆盖全区间”或“卡递归/树形假设错误：最大链树，用循环输出 parent[i]=i-1”；",
                "- 不要输出详细推理过程，只把对抗性分析的结论体现在 summary、groups.purpose 和 generatorCode 的分支设计中。",
                "",
                "【生成器接口——必须严格遵守】",
                "1. argv[1] 是随机种子，必须用 stoull(argv[1]) 或 stoll(argv[1]) 解析，严禁用 stoi；",
                "2. argv[2] 是从 1 开始的测试点编号，可用 stoi(argv[2])；",
                "3. 相同 seed 和测试点编号必须生成完全相同的输入；只能向 stdout 输出该测试点的合法输入，stderr 不输出无关内容；",
                "4. 使用 long long/unsigned long long 处理可能溢出的值，并保证生成器自身不发生越界、模零、非法随机区间或整数溢出；",
                "5. 每组输入必须严格满足题面格式、范围以及所有隐含结构约束，单个输入文件不得超过 64 MB；",
                "6. 总测试点数必须恰好为 " + request.getCaseCount() + "，每个编号都有明确且有差异的生成策略。",
                "",
                "【内存与栈安全——必须严格遵守】",
                "- 栈安全是生成器实现要求，不是数据强度要求。不得因为担心递归或栈溢出而放弃链、深树、长路径、嵌套结构、极端区间等高价值测试数据；必须保留这些卡点，并用安全的非递归方式生成；",
                "- 禁止在函数局部创建超大数组或超大对象，例如 int a[2000000]、vector<int> g[200000] 这类可能压爆栈的写法；大数据结构必须使用堆内存 vector、static/global，或按需流式输出；",
                "- 生成器代码不得依赖系统栈大小。任何递归层数可能随 n、m、q、节点数、边数、字符串长度等输入规模增长的逻辑，都必须视为有栈溢出风险；",
                "- 避免深递归 DFS/递归生成结构。若可能达到 1e4 级或更深，必须改用迭代、队列、显式栈容器或循环构造，防止 Windows 退出码 -1073741571（栈溢出）；",
                "- 生成链、深树、菊花图、扫帚图、长路径 DAG、括号/表达式、递归分治结构、嵌套区间等数据时，不要用递归函数逐层生成；应使用 for 循环、显式 stack/queue、父数组、边列表或直接公式构造；",
                "- 对一条链或深树，推荐直接用 for 循环输出边 (i-1,i) 或 parent[i]=i-1；对嵌套区间，推荐用循环输出 [i,n-i+1]；对深括号/表达式，推荐循环构造字符串，不要递归拼接；",
                "- 即使只是为了连通性检查、DFS 序、拓扑辅助、子树大小等内部辅助逻辑，也不要写可能深递归的 DFS/BFS；BFS 用 queue，DFS 用 vector<int> 或 stack<int> 显式模拟；",
                "- 生成器只负责输出输入数据，不要构建不必要的 O(n^2) 容器；边、查询、数组等应边生成边输出，或只保存必要状态；",
                "- 若上一次错误包含“退出码 -1073741571”或“栈溢出”，必须优先修复递归深度和局部大数组问题，重写为堆内存/全局内存/迭代实现；",
                "",
                "【极端结构覆盖——不得因实现风险而省略】",
                "- 如果题型涉及树/图，通常必须覆盖链、星、随机树、近满度节点、长路径、深度接近上限、稀疏/稠密边界等结构；链和深树应使用循环安全生成，而不是删掉；",
                "- 如果题型涉及区间/括号/表达式/递归定义对象，通常必须覆盖深嵌套、长连续段、完全重叠、单点、边界端点等结构；深嵌套也必须用循环构造；",
                "- 如果题型涉及查询，必须覆盖集中打同一区域、覆盖全范围、边界位置、重复查询、递增/递减/随机混合等模式；不得只生成温和随机数据；",
                "- 如果某个极端结构可能使错误算法暴露，但生成器实现存在递归风险，应改变生成器实现方式，而不是降低或删除该测试点；",
                "",
                "【数据范围分层——硬约束，必须严格执行】",
                "- 题面若出现“对于 x% 的数据，满足 ...”这类子任务/分档数据范围，x% 不是随机概率，而是测试点编号前缀约束；",
                "- 设总测试点数为 T=" + request.getCaseCount() + "，某档百分比为 x%，则前 round(T*x/100) 个测试点必须全部满足该档约束；若 T*x/100 是整数就使用该精确值，例如 T=20 且 x=40 时，测试点 1-8 必须满足 40% 档约束；",
                "- 若有多档约束，例如 40%、70%、100%，必须按编号递增分段：1..round(T*0.40) 满足 40% 档，之后到 round(T*0.70) 满足 70% 档，所有 1..T 均满足 100% 档；",
                "- 100% 数据范围是全局硬上限，所有测试点都必须满足；低百分比档通常是更小范围，不能在这些测试点中生成超出小范围的数据；",
                "- 在 groups 中必须显式写出这些编号范围和对应约束，例如“1-8: 40% 数据，n<=1000,q<=1000”；generatorCode 中必须根据测试点编号 tc 分支，强制保证对应编号范围满足对应数据范围；",
                "- 如果题面存在多变量范围（如 n、m、q、值域、边数、字符串长度等），每一档都要同时满足该档列出的全部变量限制，不得只限制其中一部分；",
                "",
                "【覆盖质量——逐项考虑】",
                "- 所有变量的最小值、最大值、相邻阈值（min+1、max-1）及题目中的关键分界点；",
                "- 对每个百分比分档分别设计边界数据：小范围档要覆盖该档自己的最大值和边界值，大范围压力数据只能放在满足对应高百分比/100% 档的编号范围内；",
                "- 空/单元素/双元素（若合法）、全相等、全不同、大量重复、单调、逆序、周期性、极度不均衡等退化结构；",
                "- 图、树、区间、字符串、排列等题型对应的链、星、稠密/稀疏、重叠、嵌套、重复字符等极端结构（仅在题意适用时使用）；",
                "- 至少一组接近最大约束的压力数据，用于淘汰暴力、错误复杂度和高常数实现；",
                "- groups 的 purpose 中要明确写出关键结构和安全生成方式，例如“最大链树，用循环输出 parent[i]=i-1，避免递归生成”；",
                "- 针对常见错误：边界偏一、错误贪心、排序假设、去重错误、状态遗漏、32 位溢出、初始化错误以及只通过随机数据的错误算法；",
                "- 随机数据要分布多样，但不能只依赖随机；避免测试点重复或仅改变随机种子而结构雷同；",
                "- 若用户要求与题面冲突，以题面合法性为最高优先级，并在 summary 中简洁说明调整。",
                ""));
        if (previousError != null && !previousError.trim().isEmpty()) {
            lines.addAll(Arrays.asList(
                    "【上一次失败信息——必须修正并避免再次出现】",
                    previousError.trim(),
                    ""));
        }
        if (previousPlan != null && previousPlan.getGeneratorCode() != null && !previousPlan.getGeneratorCode().trim().isEmpty()) {
            lines.addAll(Arrays.asList(
                    "【上一次 AI 生成器代码——请分析失败原因后重写，不要机械复用】",
                    previousPlan.getGeneratorCode(),
                    ""));
        }
        lines.addAll(Arrays.asList(
                "【题面】", request.getStatement(),
                "", "【标准程序】", request.getStandardCode(),
                "", "【出题者的数据要求】", request.getRequirements()));
        return String.join("\n", lines);
    }

    private String readableError(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message;
    }

    private DataPlan fallback(GenerationRequest request) {
        int count = request.getCaseCount();
        int smallEnd = Math.max(1, count / 4);
        int randomEnd = Math.max(smallEnd, count * 3 / 4);
        DataPlan plan = new DataPlan();
        plan.setAiGenerated(false);
        plan.setSummary("本地演示模式：生成 n 个整数并由标准程序计算输出。配置 AI 后会根据实际题意自动构造生成器。");
        plan.setEstimatedSize("小于 10 MB");
        plan.setGroups(Arrays.asList(
                new DataPlan.PlanGroup("1-" + smallEnd, "最小边界与小规模数据"),
                new DataPlan.PlanGroup((smallEnd + 1) + "-" + randomEnd, "随机与特殊排列"),
                new DataPlan.PlanGroup((randomEnd + 1) + "-" + count, "较大规模压力数据")
        ));
        plan.setGeneratorCode("#include <bits/stdc++.h>\nusing namespace std;\nint main(int argc,char**argv){\n"
                + "  long long seed=argc>1?stoll(argv[1]):1; int tc=argc>2?stoi(argv[2]):1;\n"
                + "  mt19937_64 rng(seed); int n=tc<=2?tc:(tc%5==0?10000:20+int(rng()%200));\n"
                + "  cout<<n<<'\\n'; for(int i=0;i<n;i++){ long long x=(tc%4==0?i+1:static_cast<long long>(rng()%1000000)); cout<<x<<(i+1==n?'\\n':' ');}\n}\n");
        return plan;
    }
}
