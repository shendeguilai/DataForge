package cn.datacraft.typing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Component
public class TypingArticleLibrary {
    public static final String CATEGORY_CHINESE = "中文";
    public static final String CATEGORY_ENGLISH = "英文";
    public static final String CATEGORY_CODE = "代码";
    private static final String SEED_VERSION = "typing-articles-v1";
    private static final int MAX_CONTENT_LENGTH = 12_000;
    private static final Set<String> CATEGORIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(CATEGORY_CHINESE, CATEGORY_ENGLISH, CATEGORY_CODE)));

    private final TypingArticleRepository repository;
    private final TypingArticleSeedStateRepository seedStates;
    private final ApplicationEventPublisher events;
    private volatile List<Article> articles;

    @Autowired
    public TypingArticleLibrary(TypingArticleRepository repository,
                                TypingArticleSeedStateRepository seedStates,
                                ApplicationEventPublisher events) {
        this.repository = repository;
        this.seedStates = seedStates;
        this.events = events;
        this.articles = Collections.emptyList();
    }

    // Kept package-private for fast service unit tests that do not start Spring/JPA.
    TypingArticleLibrary() {
        this.repository = null;
        this.seedStates = null;
        this.events = null;
        this.articles = immutableSorted(defaultArticles());
    }

    @PostConstruct
    public synchronized void initialize() {
        if (repository == null) return;
        if (!seedStates.existsById(SEED_VERSION)) {
            List<TypingArticleEntity> missing = defaultArticles().stream()
                    .filter(article -> !repository.existsById(article.id))
                    .map(TypingArticleLibrary::toEntity)
                    .collect(Collectors.toList());
            repository.saveAll(missing);
            seedStates.save(new TypingArticleSeedState(SEED_VERSION));
        }
        refresh();
    }

    public List<Article> all() {
        return articles;
    }

    public Article require(String id) {
        return articles.stream().filter(article -> article.id.equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("文章不存在"));
    }

    public Article random() {
        List<Article> snapshot = articles;
        if (snapshot.isEmpty()) throw new IllegalStateException("文章库为空，请先在后台添加文章");
        return snapshot.get(ThreadLocalRandom.current().nextInt(snapshot.size()));
    }

    public synchronized Article create(String title, String category, String content) {
        requirePersistentStore();
        Article article = validated(UUID.randomUUID().toString().replace("-", ""), title, category, content);
        repository.save(toEntity(article));
        refresh();
        publishChanged();
        return require(article.id);
    }

    public synchronized Article update(String id, String title, String category, String content) {
        requirePersistentStore();
        TypingArticleEntity entity = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("文章不存在"));
        Article article = validated(id, title, category, content);
        entity.update(article.title, article.category, article.content);
        repository.save(entity);
        refresh();
        publishChanged();
        return require(id);
    }

    public synchronized void delete(String id) {
        requirePersistentStore();
        if (!repository.existsById(id)) throw new NoSuchElementException("文章不存在");
        repository.deleteById(id);
        refresh();
        publishChanged();
    }

    private void refresh() {
        List<Article> loaded = repository.findAll().stream()
                .map(entity -> new Article(entity.getId(), entity.getTitle(), entity.getCategory(), entity.getContent()))
                .collect(Collectors.toList());
        articles = immutableSorted(loaded);
    }

    private void requirePersistentStore() {
        if (repository == null) throw new IllegalStateException("文章库未连接数据库");
    }

    private void publishChanged() {
        if (events != null) events.publishEvent(new ArticlesChangedEvent());
    }

    private static Article validated(String id, String rawTitle, String rawCategory, String rawContent) {
        String title = rawTitle == null ? "" : rawTitle.trim().replaceAll("\\s+", " ");
        String category = rawCategory == null ? "" : rawCategory.trim();
        String content = rawContent == null ? "" : rawContent.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (title.isEmpty() || title.length() > 80) {
            throw new IllegalArgumentException("文章标题需要 1 至 80 个字符");
        }
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("文章分类只能是中文、英文或代码");
        }
        if (content.isEmpty()) throw new IllegalArgumentException("文章内容不能为空");
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("文章内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
        }
        if (content.indexOf('\0') >= 0) throw new IllegalArgumentException("文章内容包含无效字符");
        return new Article(id, title, category, content);
    }

    private static List<Article> immutableSorted(List<Article> source) {
        List<Article> copy = new ArrayList<>(source);
        copy.sort(Comparator.comparingInt((Article article) -> categoryOrder(article.category))
                .thenComparing(article -> article.title.toLowerCase(Locale.ROOT))
                .thenComparing(article -> article.id));
        return Collections.unmodifiableList(copy);
    }

    private static int categoryOrder(String category) {
        if (CATEGORY_CHINESE.equals(category)) return 0;
        if (CATEGORY_ENGLISH.equals(category)) return 1;
        if (CATEGORY_CODE.equals(category)) return 2;
        return 3;
    }

    private static TypingArticleEntity toEntity(Article article) {
        return new TypingArticleEntity(article.id, article.title, article.category, article.content);
    }

    private static List<Article> defaultArticles() {
        return Arrays.asList(
                new Article("binary-search", "二分答案的边界", CATEGORY_CHINESE,
                        "二分查找不只能够寻找有序数组中的元素，也可以用来搜索一个满足条件的最小答案。使用这种方法时，首先要确认答案具有单调性：如果某个值可行，那么比它更宽松的值也应当可行。随后设置明确的左闭右开区间，每次取中点并调用判定函数。循环结束后，左右边界会在临界位置相遇。编写代码时要特别检查中点计算、区间更新和无解情况，避免死循环与整数溢出。把区间含义写在草稿上，通常比背诵模板更加可靠。"),
                new Article("graph-search", "图搜索与访问标记", CATEGORY_CHINESE,
                        "在图上进行深度优先搜索或广度优先搜索时，访问标记决定了每个顶点是否会被重复处理。无向图如果忘记标记父节点或已访问节点，搜索可能在两条边之间来回移动。广度优先搜索借助队列逐层扩展，因此适合计算边权相同情况下的最短路；深度优先搜索更适合遍历连通块、维护递归状态和处理树形结构。无论选择哪一种方式，都应先估算顶点数与边数，再决定邻接表、邻接矩阵以及递归是否可能超过栈空间。"),
                new Article("prefix-sum", "前缀和的区间视角", CATEGORY_CHINESE,
                        "前缀和把一段区间的重复累加转换成两个端点之间的减法。设前缀数组表示前若干个元素的总和，那么任意连续区间都可以在常数时间内求出。最常见的错误来自下标定义不一致：有的写法让零号位置表示空前缀，有的写法直接从第一个元素开始累计。二维前缀和还需要使用容斥，依次加上目标矩形右下角的前缀，减去上方和左方区域，再补回被减去两次的交集。先画出区域，再写公式，会让边界更清晰。"),
                new Article("dynamic-programming", "动态规划的状态设计", CATEGORY_CHINESE,
                        "动态规划的难点通常不是转移方程，而是选择恰当的状态。一个有效状态应当包含决定未来所需的全部信息，同时尽量删除不会影响后续决策的细节。确定状态后，需要说明它表示什么、从哪些较小状态转移而来、初始值是什么，以及最终答案位于哪里。若转移依赖尚未计算的位置，就要重新安排枚举顺序。空间优化也应建立在依赖关系清楚之后，不能为了少开一个数组而覆盖仍然需要的数据。先写正确版本，再考虑压缩，往往更稳妥。"),
                new Article("complexity", "复杂度与数据范围", CATEGORY_CHINESE,
                        "阅读题目时，数据范围往往比故事背景更早暴露解法方向。规模只有几十时，可以考虑状态压缩或较高次复杂度；规模达到十万时，通常需要线性或对数级算法；如果数据接近十亿，就不应逐个枚举。复杂度分析不仅要数循环层数，还要关注每次操作的真实代价，例如字符串复制、平衡树查询和哈希冲突。估算通过后仍要为常数、内存和最坏输入留出余量。先根据范围排除不可行方案，再证明剩余方案的正确性，能够减少无效编码。"),
                new Article("disjoint-set", "并查集维护连通性", CATEGORY_CHINESE,
                        "并查集维护若干互不相交的集合，最常见的用途是判断两个元素是否已经连通。每个集合由一个代表元标识，查找操作沿父指针到达根节点，合并操作则把两个根连接起来。路径压缩会在查找后缩短沿途节点到根的距离，按大小或按秩合并可以避免树退化得过深。两种优化一起使用时，单次操作在实际数据中几乎可以看作常数时间。并查集擅长回答连通关系，但不能直接恢复具体路径，也不适合随意删除已经加入的边。"),
                new Article("shortest-path", "最短路算法的选择", CATEGORY_CHINESE,
                        "最短路问题需要根据边权性质选择算法。所有边权相同可以直接使用广度优先搜索；边权非负时，优先队列优化的算法通常能够高效工作；存在负边时，则必须考虑能够处理负权的方案，并判断负环是否让答案失去意义。实现过程中，距离数组应使用足够大的整数类型，无穷大也要留出加法空间。优先队列中可能保留旧状态，取出节点后应检查当前距离是否仍然有效。算法名称并不重要，真正重要的是它的前提是否与题目条件一致。"),
                new Article("testing", "用样例之外的数据检查程序", CATEGORY_CHINESE,
                        "样例只能说明程序在少数输入上表现正确，不能代替完整验证。提交之前，可以主动构造最小规模、最大规模、全部相等、严格递增、答案不存在以及恰好卡在边界上的数据。对于容易写错的算法，还可以准备一个速度较慢但逻辑直接的暴力程序，再随机生成小数据与正式程序对拍。发现差异后保存输入，逐步缩小问题规模，通常能很快定位错误。良好的测试习惯并不会拖慢比赛节奏，反而能减少反复提交带来的罚时。"),
                new Article("memory-model", "变量、栈与堆空间", CATEGORY_CHINESE,
                        "程序运行时，不同数据拥有不同的生命周期和存储位置。函数中的普通局部变量通常位于调用栈，函数返回后相应空间会被回收；动态申请的对象位于堆上，需要由语言运行时或程序员管理。规模很大的局部数组可能导致栈空间不足，而全局数组或动态容器通常拥有更宽松的容量。递归层数过深同样会消耗大量栈空间。排查运行错误时，除了检查数组越界和空指针，也应结合数据规模估算实际占用的字节数。"),
                new Article("contest-routine", "稳定的比赛解题流程", CATEGORY_CHINESE,
                        "一场算法比赛开始后，可以先快速浏览全部题目，记录每题的输入规模、核心目标和初步想法，再从最有把握的题目开始。编码前用几句话写清算法与复杂度，能够及时发现遗漏条件。完成程序后先编译，再依次检查样例、边界和自造数据；遇到长时间没有进展的题目时，暂时切换到其他任务往往更有效。最后阶段应谨慎修改已经通过测试的代码，并为提交、文件名和整数范围留出检查时间。稳定的流程能让注意力更多地用在真正困难的思考上。"),

                new Article("english-clear-thinking", "Clear Thinking Under Pressure", CATEGORY_ENGLISH,
                        "A programming contest rewards calm decisions as much as fast typing. Read the limits before choosing an algorithm, write down the meaning of every state, and test the smallest possible case before trusting a solution. When a sample fails, avoid changing several parts at once. Form one hypothesis, create an input that can prove it wrong, and inspect the program at the first place where reality differs from your expectation. A short pause can save many rushed submissions."),
                new Article("english-debugging", "Debugging With Evidence", CATEGORY_ENGLISH,
                        "Good debugging begins with evidence. Save the exact input that exposes the problem, reduce it until every remaining detail matters, and compare the expected state with the actual state after each important operation. Boundary errors often appear at empty ranges, the first element, the last element, or values near an integer limit. Logging less information at carefully chosen points is usually more useful than printing everything. Once the cause is clear, add a test that would fail if the bug returned."),
                new Article("english-teamwork", "Learning Algorithms Together", CATEGORY_ENGLISH,
                        "Explaining an algorithm to another student is one of the best ways to discover gaps in your own understanding. Start with the problem goal, then describe the invariant that remains true while the algorithm runs. Use a small example and trace each change by hand. If the explanation depends on phrases such as obviously or somehow, slow down and make that step precise. A clear explanation should cover correctness, complexity, edge cases, and the reason each data structure was selected."),
                new Article("english-small-steps", "Progress Through Small Steps", CATEGORY_ENGLISH,
                        "Large problems become manageable when they are divided into small, verifiable steps. First solve a tiny version by hand. Next write a direct solution, even if it is too slow for the final limits. Study which work is repeated, and replace that repeated work with a stored result, a better data structure, or a mathematical observation. Keep the slow version as an oracle for random tests. Optimization is safest when every improvement preserves behavior that has already been checked."),

                new Article("code-prefix-sum", "C++ 前缀和区间查询", CATEGORY_CODE,
                        "#include<bits/stdc++.h>\nusing namespace std;\nconst int N = 100010;\nlong long a[N], sum[N];\nint main() {\n    int n, m;\n    cin >> n >> m;\n    for (int i = 1; i <= n; i++) {\n        cin >> a[i];\n        sum[i] = sum[i - 1] + a[i];\n    }\n    while (m--) {\n        int l, r;\n        cin >> l >> r;\n        cout << sum[r] - sum[l - 1] << '\\n';\n    }\n    return 0;\n}"),
                new Article("code-binary-search", "C++ 二分查找首个位置", CATEGORY_CODE,
                        "#include<bits/stdc++.h>\nusing namespace std;\nconst int N = 1000010;\nint a[N];\nint main() {\n    int n, target;\n    cin >> n >> target;\n    for (int i = 1; i <= n; i++) {\n        cin >> a[i];\n    }\n    int left = 1, right = n;\n    while (left < right) {\n        int mid = (left + right) / 2;\n        if (a[mid] >= target) right = mid;\n        else left = mid + 1;\n    }\n    if (a[left] == target) cout << left;\n    else cout << -1;\n    return 0;\n}"),
                new Article("code-stairs", "C++ 递推爬楼梯", CATEGORY_CODE,
                        "#include<bits/stdc++.h>\nusing namespace std;\nlong long f[1010];\nint main() {\n    int n;\n    cin >> n;\n    f[0] = 1;\n    f[1] = 1;\n    for (int i = 2; i <= n; i++) {\n        f[i] = f[i - 1] + f[i - 2];\n    }\n    cout << f[n] << '\\n';\n    return 0;\n}"),
                new Article("code-brackets", "C++ 栈判断括号匹配", CATEGORY_CODE,
                        "#include<bits/stdc++.h>\nusing namespace std;\nchar stk[1010];\nint top = 0;\nint main() {\n    string s;\n    cin >> s;\n    bool ans = true;\n    for (int i = 0; i < s.size(); i++) {\n        if (s[i] == '(' || s[i] == '[') {\n            stk[++top] = s[i];\n        } else {\n            char need;\n            if (s[i] == ')') need = '(';\n            else need = '[';\n            if (top == 0 || stk[top] != need) {\n                ans = false;\n                break;\n            }\n            top--;\n        }\n    }\n    if (top != 0) ans = false;\n    if (ans) cout << \"OK\";\n    else cout << \"WRONG\";\n    return 0;\n}")
        );
    }

    public static final class Article {
        private final String id;
        private final String title;
        private final String category;
        private final String content;

        Article(String id, String title, String category, String content) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.content = content;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getCategory() { return category; }
        public String getContent() { return content; }
        public int getLength() { return content.length(); }
    }

    public static final class ArticlesChangedEvent {}
}
