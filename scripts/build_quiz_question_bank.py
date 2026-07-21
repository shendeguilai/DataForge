#!/usr/bin/env python3
"""Turn local Vision OCR output into the versioned J-group quiz bank."""

from __future__ import annotations

import argparse
import json
import re
import unicodedata
from pathlib import Path


CATEGORIES = {"计算机基础", "C++语法", "算法", "数据结构", "奥数与逻辑"}
FOOTER_MARKERS = ("可达信奥", "Talk is cheap")
CONTINUATION_ENDINGS = tuple("一配设程元节问结表函公算方类数整形过骤部个为或与将的")


def normalize(text: str) -> str:
    value = unicodedata.normalize("NFKC", text)
    replacements = {
        "竟赛": "竞赛", "NOl": "NOI", "NOL": "NOI", "Iinux": "Linux",
        "string:npos": "string::npos", "std:sort": "std::sort", "std:：sort": "std::sort",
        "synC_with_stdio": "sync_with_stdio", "synC_with": "sync_with", "O(og n)": "O(log n)",
        "0(log n)": "O(log n)", "0(n)": "O(n)", "0(1)": "O(1)", "0(n^2)": "O(n^2)",
        "0(n2)": "O(n^2)", "0(n log n)": "O(n log n)", "0(nlog n)": "O(n log n)",
        "O(n2)": "O(n^2)", "O(nz)": "O(n^2)", "sqrt(n)": "√n",
        "C+ +": "C++", "C+\n+": "C++", "-02": "-O2", "-0指定": "-o 指定",
        "\u00d7": "x", "fin d": "find", "getl ine": "getline",
        "pri ntf": "printf", "synC_wit h_stdio": "sync_with_stdio", "iom anip": "iomanip",
        "i gnore": "ignore", "po p": "pop", "R untime": "Runtime", "GC D": "GCD",
        "Coprim e": "Coprime", "A SCII": "ASCII", "I Pv4": "IPv4", "doubl e": "double",
        "struc t": "struct", "priority_que ue": "priority_queue", "gr eater": "greater",
        "sqr t": "sqrt", "RA M": "RAM", "Proble m": "Problem", "e ps": "eps",
        "defi ne": "define", "b reak": "break", "Tr ee": "Tree", "Recursio n": "Recursion",
        "Simulati on": "Simulation", "Fibonacc i": "Fibonacci", "Eratosthene s": "Eratosthenes",
        "Brute Forc e": "Brute Force", "Binary Searc h": "Binary Search",
        "Selection So rt": "Selection Sort", "Insertion Sor t": "Insertion Sort",
        "NO竞赛": "NOI 竞赛", "NOI L inux": "NOI Linux", "N OIP": "NOIP",
        "Byt e": "Byte", "LC M": "LCM", "Sor t": "Sort", "Counti ng": "Counting",
        "A rray": "Array", "Divisi on": "Division", "F actorial": "Factorial",
        "Exponentia tion": "Exponentiation", "V ector": "vector", "vi s": "vis",
        "targe t": "target", "1ong": "long", "1imit": "limit", "0ff-by-one": "Off-by-one",
        "102 4": "1024", "25 6": "256", "100 0": "1000", "10 48576": "1048576",
        "2 e9": "2e9", "1 0^18": "10^18", "10^1 9": "10^19",
    }
    for old, new in replacements.items():
        value = value.replace(old, new)
    value = re.sub(r"(?<![A-Za-z])Is(?=命令|、|是|\s|$)", "ls", value)
    value = re.sub(r"\b0\((?=(?:log|n|1))", "O(", value)
    value = value.replace("O(lo g", "O(log").replace("O(nl og n)", "O(n log n)")
    value = re.sub(r"\bstd:(?!:)", "std::", value)
    value = re.sub(r"\bls-\|\b|\bIs-\|\b|\bls-l\b", "ls -l", value)
    value = re.sub(r"\bCp\b|\bCP\b", "cp", value)
    value = re.sub(r"\s+([,.;:!?，。；：！？])", r"\1", value)
    value = re.sub(r"([，。；：！？])\s+", r"\1", value)
    value = re.sub(r"\s{2,}", " ", value)
    return value.strip()


def compact_join(lines: list[str]) -> str:
    if not lines:
        return ""
    result = normalize(lines[0])
    for raw in lines[1:]:
        current = normalize(raw)
        if not current:
            continue
        needs_space = bool(result and current and result[-1].isascii() and current[0].isascii()
                           and result[-1].isalnum() and current[0].isalnum())
        result += (" " if needs_space else "") + current
    return normalize(result)


def find_cards(raw_cards: list[dict]) -> dict[str, dict[str, list[str]]]:
    pairs: dict[str, dict[str, list[str]]] = {}
    for card in raw_cards:
        match = re.search(r"J\d{3}", card["fileName"])
        if not match:
            continue
        side = "front" if "正面" in card["fileName"] else "back"
        pairs.setdefault(match.group(), {})[side] = card["lines"]
    return pairs


def front_fields(lines: list[str]) -> tuple[str, str, str]:
    normalized = [normalize(line) for line in lines]
    category = next((line for line in normalized if line in CATEGORIES), "计算机基础")
    difficulty = next((line for line in normalized if line.endswith("级")), "入门级")
    content = [line for line in normalized
               if line != "OI CARD" and line != category and line != difficulty
               and not any(marker in line for marker in FOOTER_MARKERS)]
    prompt = compact_join(content).replace("find0函数", "find() 函数")
    return category, difficulty, prompt


def back_fields(lines: list[str]) -> tuple[str, str, str | None, str | None]:
    cleaned: list[str] = []
    code: list[str] = []
    in_code = False
    for raw in lines:
        line = normalize(raw)
        if not line or any(marker in line for marker in FOOTER_MARKERS):
            continue
        if "答案" in line or "笞案" in line or re.fullmatch(r"[IT]D:J?\d{3}", line):
            continue
        if "CODE SNIPPET" in line:
            in_code = True
            continue
        if line in {"•", "·"}:
            continue
        (code if in_code else cleaned).append(line)

    if not cleaned:
        return "待校订", "原题卡未能识别出解析，请以题面知识点为准。", None, None

    answer_line_count = 1
    if len(cleaned) > 1:
        first, second = cleaned[0], cleaned[1]
        first_complete = first.endswith(("。", "；", ";", "!", "！"))
        likely_wrapped = (not first_complete and len(second) <= 24
                          and (first.endswith(CONTINUATION_ENDINGS) or second.endswith(("。", "；", ";"))))
        if likely_wrapped:
            answer_line_count = 2
    answer = compact_join(cleaned[:answer_line_count]).rstrip("。")
    explanation = compact_join(cleaned[answer_line_count:])
    if not explanation:
        explanation = f"这道题的直接答案是“{answer}”。回答时还应结合题目条件说明它的具体含义。"

    example = compact_join(code) or None
    if example and suspicious_code_example(example):
        example = None
    pitfall = None
    for sentence in re.split(r"(?<=[。！？])", explanation):
        if any(keyword in sentence for keyword in ("注意", "不要", "警惕", "避免", "必须", "不能")):
            pitfall = sentence.strip()
            break
    return answer, explanation, example, pitfall


def suspicious_code_example(example: str) -> bool:
    artifacts = (
        "••", "•.", "alj", "ali]", "alil", "isn", "is=n", "atn", "queuesint",
        "stacksint", "priority_queuesint", "couts", "mainO", "rest+", "visli", "dli",
        "Sfi", "sumfi", "1ong", "b== 8", "b== 9", "s td::", "st d::", "[101",
    )
    if any(artifact in example for artifact in artifacts):
        return True
    return (example.count("(") != example.count(")")
            or example.count("{") != example.count("}")
            or example.count("[") != example.count("]"))


def apply_overrides(question: dict) -> None:
    if question["id"] == "J006":
        question.update({
            "answer": "ls",
            "explanation": "ls 是 Linux 中用于列出目录内容的命令。不带参数时，它会显示当前目录中的文件和子目录；也可以在命令后给出路径，查看指定目录的内容。",
            "example": "ls 查看当前目录；ls -l 显示权限、所有者、大小和修改时间等详细信息；ls -a 还会显示以点开头的隐藏文件。",
            "pitfall": "命令是小写字母 l 和 s，不是大写字母 I；ls -l 中的参数也是小写字母 l。",
        })
    elif question["id"] == "J031":
        question["explanation"] = question["explanation"].replace("趋近于O(n)", "接近 O(n)")
    elif question["id"] == "J057":
        question.update({
            "answer": "ls -l",
            "explanation": "ls 用于列出目录内容，-l 表示使用长格式显示。输出中会包含文件类型和权限、硬链接数、所有者、所属组、大小、修改时间以及名称。",
            "example": "在终端执行 ls -l；若还需要显示隐藏文件，可以使用 ls -la。",
            "pitfall": "-l 是小写字母 l，不是数字 1，也不是竖线符号。",
        })
    elif question["id"] == "J193":
        question.update({
            "answer": "从小到大枚举尚未被标记的数，并把它从平方开始的倍数标记为合数",
            "explanation": "埃氏筛利用“合数一定含有质因数”的性质：从 2 开始，未被标记的数就是质数，再标记它的倍数。倍数可以从 p² 开始，因为更小的倍数已经被更小的质数处理过。整体复杂度约为 O(n log log n)。",
            "example": "筛到 5 时，10、15、20 已被 2 或 3 标记，因此直接从 25 开始标记 25、30、35……即可。",
            "pitfall": "1 既不是质数也不是合数；实现时要注意 p*p 可能溢出，应使用足够大的整数类型。",
        })
    elif question["id"] == "J049":
        question.update({
            "answer": "2^n 个",
            "explanation": "集合中的每个元素都有“选入子集”和“不选入子集”两种彼此独立的选择，因此 n 个元素共有 2×2×…×2=2^n 种子集。这个数量包含空集和原集合本身。",
            "example": "集合 {a,b,c} 有 2^3=8 个子集。",
            "pitfall": "不要漏掉空集，也不要把真子集数量和子集总数混淆；真子集共有 2^n-1 个。",
        })
    elif question["id"] == "J113":
        question.update({
            "answer": "12 个",
            "explanation": "a[3][4] 表示 3 行、每行 4 个元素，因此元素总数为 3×4=12。C++ 二维数组按行优先连续存储。",
            "example": "合法下标是 a[0][0] 到 a[2][3]。",
            "pitfall": "下标从 0 开始，所以 a[3][4] 中的 3 和 4 是长度，不是最大下标。",
        })
    elif question["id"] == "J114":
        question.update({
            "promptText": "逻辑运算符 && 的名称和特性是什么？",
            "answer": "逻辑与；两边都为真时结果才为真，并且具有短路求值特性",
            "explanation": "&& 是逻辑与运算符。只有左右表达式都为真时结果才为真；如果左侧已经为假，整体结果必为假，因此右侧不会再执行。",
            "example": "i < n && a[i] > 0 会先检查 i 是否越界，只有 i < n 时才访问 a[i]。",
            "pitfall": "&& 是逻辑与，& 是按位与；两者含义不同。",
        })
    elif question["id"] == "J135":
        question.update({
            "promptText": "如何访问 struct Node { int x, y; } 类型变量 p 的成员 x？",
            "answer": "p.x",
            "explanation": "普通结构体对象使用点运算符访问成员，因此变量 p 的成员 x 写作 p.x。只有当变量是指向结构体的指针时，才使用箭头运算符。",
            "example": "Node p; p.x = 10; Node* ptr = &p; ptr->x = 20;",
            "pitfall": "对象使用 .，指针使用 ->，不要混用。",
        })
    elif question["id"] == "J171":
        question.update({
            "answer": "不断除以 N 记录余数，最后将余数逆序；10～35 映射为 A～Z",
            "explanation": "十进制整数每次除以基数 N，余数就是当前最低位；再用商继续除，直到商为 0。因为先得到低位，所以最终需要逆序输出。N≤36 时可用 0～9 和 A～Z 表示数码。",
            "example": "十进制 31 转十六进制：31÷16=1 余15，1÷16=0 余1，逆序并把15写成F，得到 1F。",
            "pitfall": "输入为 0 时应直接输出 0；负数需要单独处理符号。",
        })
    elif question["id"] == "J196":
        question.update({
            "answer": "除 K 取余并逆序排列",
            "explanation": "每次计算 n%K 得到当前最低位，再令 n/=K 继续处理更高位；商变为 0 后，将记录的余数从后向前输出，就得到 K 进制表示。",
            "example": "13 转二进制依次得到余数 1、0、1、1，逆序后为 1101。",
            "pitfall": "必须单独处理 n=0，否则循环不会执行而导致没有输出。",
        })
    elif question["id"] == "J200":
        question.update({
            "answer": "最优子结构、重叠子问题和无后效性",
            "explanation": "动态规划把原问题表示为若干状态，并通过状态转移复用已经计算的子问题结果。最优解可以由子问题最优解构成，子问题会重复出现，并且确定当前状态后，后续决策不需要知道更早的完整过程。",
            "example": "01 背包用 dp[j] 表示容量为 j 时的最大价值，并由较小容量的状态转移得到。",
            "pitfall": "动态规划的关键不是套循环，而是先明确状态含义、初始状态和转移顺序。",
        })
    elif question["id"] == "J201":
        question.update({
            "answer": "dp[j] = max(dp[j], dp[j-w[i]] + v[i])，且容量 j 必须逆序遍历",
            "explanation": "对第 i 件物品，dp[j] 表示容量不超过 j 时的最大价值：不选它时保持 dp[j]，选它时由 dp[j-w[i]] 加上价值 v[i] 转移。逆序遍历能保证每件物品最多使用一次。",
            "example": "for (int j = W; j >= w[i]; --j) dp[j] = max(dp[j], dp[j-w[i]] + v[i]);",
            "pitfall": "若容量正序遍历，同一件物品可能在一轮中被重复使用，算法就变成完全背包。",
        })
    elif question["id"] == "J205":
        question.update({
            "answer": "s[i][j] = s[i-1][j] + s[i][j-1] - s[i-1][j-1] + a[i][j]",
            "explanation": "二维前缀和把左上矩形拆成上方矩形和左侧矩形，两者重叠的左上部分被计算两次，所以要减去一次，再加上当前位置元素。",
            "example": "矩形 (x1,y1)～(x2,y2) 的和为 s[x2][y2]-s[x1-1][y2]-s[x2][y1-1]+s[x1-1][y1-1]。",
            "pitfall": "通常额外保留第 0 行和第 0 列为 0，以避免边界判断和负下标。",
        })
    elif question["id"] == "J209":
        question.update({
            "answer": "左移 k 位相当于乘 2^k；非负整数右移 k 位相当于整除 2^k",
            "explanation": "二进制整体左移一位会在末尾补 0，因此数值乘 2；右移一位会丢弃最低位，因此对非负整数相当于向下整除 2。移动 k 位就是对应 2^k。",
            "example": "x << 3 相当于 x×8；非负整数 x >> 2 相当于 x÷4。",
            "pitfall": "左移可能溢出；负数右移以及超出类型位数的移位不应依赖简单的乘除结论。",
        })
    elif question["id"] == "J213":
        question.update({
            "answer": "自变量变化时，结果始终保持同一方向变化；可据此使用二分、双指针等方法",
            "explanation": "单调性表示条件或函数值随着参数增大只会从小到大、从大到小，或只发生一次真假分界。算法可以利用这个分界排除一半答案，或让指针只向一个方向移动。",
            "example": "若长度 L 越小越容易满足要求，就可以二分寻找最大的可行 L。",
            "pitfall": "使用二分答案前必须证明可行性关于答案具有单调性，不能只因为答案是数字就二分。",
        })
    elif question["id"] == "J215":
        question.update({
            "answer": "|x1-x2| + |y1-y2|",
            "explanation": "曼哈顿距离表示只能沿水平和竖直方向移动时，从一点到另一点所需的最少总步数，因此等于横坐标差的绝对值加纵坐标差的绝对值。",
            "example": "点 (1,2) 到 (4,6) 的曼哈顿距离是 |1-4|+|2-6|=7。",
            "pitfall": "它不是两点直线距离；欧几里得距离需要平方、求和再开方。",
        })
    elif question["id"] == "J218":
        question.update({
            "answer": "输入范围较小、答案可预计算，或规律复杂但结果数量有限时",
            "explanation": "打表法是在程序运行前或查询前计算好所有可能结果，再把结果存入数组或常量表中。它用额外存储换取查询速度，也适合验证规律和处理具有短周期的数据。",
            "example": "预先计算 1～10^6 内每个数是否为质数，之后每次查询只需 O(1) 读取标记。",
            "pitfall": "要确认表的大小满足内存限制，并保证预计算本身不会超时。",
        })
    elif question["id"] == "J222":
        question.update({
            "answer": "枚举 0～2^n-1，用二进制第 i 位表示第 i 个元素是否被选择",
            "explanation": "n 个元素的每个子集都能唯一对应一个 n 位二进制掩码。某一位为 1 表示选择对应元素，为 0 表示不选择，因此遍历全部掩码即可生成所有子集。",
            "example": "集合 {a,b,c} 中，掩码 101 表示选择 a 和 c。",
            "pitfall": "状态数是 2^n，n 稍大就会迅速增长；写 1<<n 时还要注意整数类型溢出。",
        })
    elif question["id"] == "J229":
        question.update({
            "answer": "操作系统是管理硬件与软件资源、为应用程序提供运行环境的系统软件；例如 Linux、Windows、macOS",
            "explanation": "操作系统负责进程调度、内存管理、文件系统、设备控制和用户交互，并在硬件与应用程序之间提供统一接口。信息学竞赛常用 Linux 环境。",
            "example": "程序读取文件时会通过操作系统提供的文件接口访问磁盘，而不需要直接控制磁盘硬件。",
            "pitfall": "操作系统不是普通应用软件，也不等同于某个图形桌面或命令行程序。",
        })
    elif question["id"] == "J238":
        question.update({
            "answer": "全局变量定义在函数外、作用域更广并默认零初始化；普通局部变量定义在块或函数内，未显式初始化时值不确定",
            "explanation": "全局变量具有静态存储期，程序开始时完成零初始化；局部自动变量通常只在进入所在作用域时创建，离开后销毁。两者还存在可见范围和内存区域的区别。",
            "example": "全局 int count; 初值为 0；函数内 int count; 若没有赋值就读取，会产生未定义行为。",
            "pitfall": "局部变量不是“随机初始化”，而是值不确定；读取未初始化的局部变量属于错误行为。",
        })


CURATED_FIXES = {
    "J002": {
        "answer": "使用 %lld 占位符，例如 printf(\"%lld\\n\", x);",
        "explanation": "printf 按占位符解释后续参数。int 通常使用 %d，long long 必须使用 %lld；占位符与变量类型不匹配会产生未定义行为。也可以直接使用 cout << x。",
        "example": "long long x = 1234567890123LL; printf(\"%lld\\n\", x);",
        "pitfall": "%lld 中是两个小写字母 l，不是数字 1；不要用 %d 输出 long long。",
    },
    "J011": {
        "answer": "1",
        "explanation": "先计算括号中的 0 OR 1，结果为 1；再计算 1 AND 1，结果仍为 1。逻辑运算中 AND 表示两者都真才真，OR 表示至少一个为真就为真。",
        "example": "在 C++ 中可写为 1 && (0 || 1)，表达式结果为 true。",
        "pitfall": "C++ 的逻辑或是 ||，单个 | 是按位或，含义不同。",
    },
    "J015": {
        "answer": "左侧已经能决定整个逻辑表达式的结果时，右侧不再求值",
        "explanation": "对于 A && B，若 A 为假，整体必为假，因此 B 不执行；对于 A || B，若 A 为真，整体必为真，因此 B 不执行。短路求值常用于先检查边界，再访问数组或指针。",
        "example": "i < n && a[i] > 0：只有 i < n 时才会访问 a[i]。",
        "pitfall": "逻辑或写作 ||，不是按位或 |；不要依赖短路执行带来难以理解的副作用。",
    },
    "J016": {
        "answer": "0 到 9",
        "explanation": "int a[10] 声明 10 个元素，C++ 数组下标从 0 开始，所以第一个元素是 a[0]，最后一个是 a[9]。",
        "example": "for (int i = 0; i < 10; ++i) a[i] = i;",
        "pitfall": "a[10] 已越界；方括号里的 10 是元素个数，不是最大合法下标。",
    },
    "J017": {
        "answer": "使用 getline(cin, s)",
        "explanation": "cin >> s 遇到空格就停止，而 getline 会读取到本行换行符之前的全部字符，因此适合读取含空格的姓名或句子。",
        "example": "string s; getline(cin, s);",
        "pitfall": "若前面刚使用 cin >> n，通常要先执行 cin.ignore(numeric_limits<streamsize>::max(), '\\n') 清掉残留换行。",
    },
    "J019": {
        "answer": "在局部作用域内，局部变量会遮蔽同名全局变量",
        "explanation": "编译器优先使用离当前位置最近的可见声明，因此函数或代码块中的同名局部变量会让全局变量暂时不可见；离开该作用域后，全局变量仍然存在。",
        "example": "int x=1; int main(){ int x=2; cout << x; } // 输出 2",
        "pitfall": "遮蔽不是覆盖全局变量的值；同名会降低可读性，应尽量避免。",
    },
    "J024": {
        "answer": "O(n^2)",
        "explanation": "冒泡排序最多进行 n-1 轮，每轮比较相邻元素，总比较次数约为 n(n-1)/2，因此最坏时间复杂度为 O(n^2)。",
        "example": "数组 3,2,1 需要多轮相邻比较和交换才能变为 1,2,3。",
        "pitfall": "即使加入“本轮无交换则停止”的优化，最坏情况仍是 O(n^2)。",
    },
    "J025": {
        "answer": "长度可动态变化，并提供 size、push_back 等安全易用的容器操作",
        "explanation": "vector 是连续存储的动态数组，支持 O(1) 下标访问和均摊 O(1) 的尾部插入；容量不足时会重新申请更大的连续空间并搬移元素。",
        "example": "vector<int> v; v.push_back(3); cout << v[0];",
        "pitfall": "扩容可能使原有指针、引用和迭代器失效；已知规模时可先 reserve。",
    },
    "J027": {
        "answer": "每轮在未排序区间中找到最小元素，并与该区间的第一个位置交换",
        "explanation": "第 i 轮确定第 i 小的元素，经过 n-1 轮后数组有序。比较次数固定约为 n(n-1)/2，所以时间复杂度为 O(n^2)。",
        "example": "3,1,2 的第一轮选出 1 与 3 交换，得到 1,3,2；第二轮得到 1,2,3。",
        "pitfall": "直接交换实现的选择排序通常不稳定，相等元素的原相对次序可能改变。",
    },
    "J031": {
        "answer": "当数组已经有序或基本有序时",
        "explanation": "插入排序只需把当前元素向前移动到合适位置。若逆序对很少，每个元素移动次数也很少，运行时间可接近 O(n)；最坏情况仍为 O(n^2)。",
        "example": "序列 1,2,3,5,4 中，最后的 4 只需向前移动一位。",
        "pitfall": "“基本有序时快”不等于最坏复杂度变成 O(n)；完全逆序时仍需大量移动。",
    },
    "J033": {
        "answer": "后进先出（LIFO）",
        "explanation": "栈只在栈顶插入和删除：push 入栈、pop 出栈、top 查看栈顶。最后放入的元素最先取出。",
        "example": "依次压入 1、2、3，随后三次弹出的顺序是 3、2、1。",
        "pitfall": "调用 top 或 pop 前必须确认栈非空。",
    },
    "J034": {
        "answer": "先进先出（FIFO）",
        "explanation": "队列在队尾加入元素，在队头移除元素，常用操作有 push、front 和 pop；最早进入的元素最先离开。",
        "example": "依次入队 A、B、C，出队顺序是 A、B、C。",
        "pitfall": "调用 front 或 pop 前必须确认队列非空。",
    },
    "J036": {
        "promptText": "图的邻接矩阵中，a[i][j] = 1 表示什么？",
        "answer": "表示顶点 i 到顶点 j 之间存在一条边",
        "explanation": "邻接矩阵用二维数组记录顶点之间是否相邻。无权图通常用 0/1 表示无边或有边；无向图的矩阵关于主对角线对称。",
        "example": "若 a[2][5]=1，则顶点 2 与顶点 5 之间有边；无向图还应有 a[5][2]=1。",
        "pitfall": "有向图中 a[i][j] 与 a[j][i] 含义不同；带权图通常存边权而不是只存 1。",
    },
    "J037": {
        "answer": "2^h - 1 个（根节点为第 1 层）",
        "explanation": "满二叉树第 i 层有 2^(i-1) 个节点，把第 1 层到第 h 层相加，得到 1+2+…+2^(h-1)=2^h-1。",
        "example": "高度为 3 时共有 1+2+4=7 个节点。",
        "pitfall": "要先确认高度从 0 还是从 1 计数；本题规定根节点在第 1 层。",
    },
    "J038": {
        "answer": "已定位插入或删除位置时，只需修改相邻链接，不必移动后续大量元素",
        "explanation": "数组中间插入通常要移动后续元素，而链表只需修改指针或后继下标。不过链表不支持 O(1) 随机访问，寻找位置本身可能需要 O(n)。",
        "example": "已知节点 p 后插入 q，只需令 q.next=p.next，再令 p.next=q。",
        "pitfall": "不能笼统地说任意位置插入都是 O(1)：如果尚未找到该位置，查找仍可能是 O(n)。",
    },
    "J039": {
        "promptText": "完全二叉树按 1 开始编号时，编号为 i 的父节点，其左子节点编号是多少？",
        "answer": "2*i",
        "explanation": "完全二叉树按层从左到右编号时，节点 i 的左孩子是 2i，右孩子是 2i+1，父节点是 floor(i/2)。",
        "example": "节点 3 的左孩子编号为 6，右孩子编号为 7。",
        "pitfall": "这些公式以根节点编号为 1 为前提；若从 0 编号，公式会不同。",
    },
    "J043": {
        "answer": "先排除 n<2，再检查 2 到 √n 是否存在能整除 n 的因数",
        "explanation": "若 n 是合数，它的一对因数中至少有一个不超过 √n，所以只需试除到 √n；没有找到因数时，n 才是质数。",
        "example": "判断 29 时只需检查 2、3、4、5，均不能整除，所以 29 是质数。",
        "pitfall": "0 和 1 不是质数；循环可写 i <= n/i，避免 i*i 溢出。",
    },
    "J044": {
        "answer": "最大公约数是两个整数公有约数中的最大者；常用欧几里得算法（辗转相除法）",
        "explanation": "利用 gcd(a,b)=gcd(b,a%b) 不断缩小问题，当 b=0 时，|a| 就是最大公约数。算法复杂度为 O(log min(a,b))。",
        "example": "gcd(48,18) → gcd(18,12) → gcd(12,6) → 6。",
        "pitfall": "C++17 可使用 std::gcd；处理负数时应明确结果取非负值。",
    },
    "J048": {
        "answer": "真变假，假变真",
        "explanation": "逻辑非对一个布尔值取反。在 C++ 中使用 !：!true 为 false，!false 为 true；数值 0 被视为假，非 0 被视为真。",
        "example": "若 bool ok=false，则 !ok 的值为 true。",
        "pitfall": "! 是逻辑非，~ 是按位取反，两者不能混用。",
    },
    "J052": {
        "answer": "能被 400 整除，或能被 4 整除但不能被 100 整除",
        "explanation": "普通年份能被 4 整除是闰年，但整百年份必须还能被 400 整除。因此条件是 (y%400==0) || (y%4==0 && y%100!=0)。",
        "example": "2000 年是闰年，1900 年不是闰年，2024 年是闰年。",
        "pitfall": "不能只判断 y%4==0，否则会把 1900 这类整百年误判为闰年。",
    },
    "J055": {
        "answer": "ASCII 是美国信息交换标准代码；'0'=48，'A'=65，'a'=97",
        "explanation": "标准 ASCII 使用 7 位定义 128 个字符，包括控制字符、数字、英文字母和常用符号。C++ 中 char 可参与整数运算，因此常利用连续编码处理数字和字母。",
        "example": "字符数字转数值可写 int d = ch - '0'；小写英文字母与对应大写字母编码相差 32。",
        "pitfall": "ASCII 本身是 7 位、共 128 个编码；不要把各种 8 位扩展编码都称为标准 ASCII。",
    },
    "J058": {
        "answer": "32 位，也就是 4 个字节",
        "explanation": "IPv4 地址由 32 个二进制位组成，常写成四段点分十进制，每段对应 8 位，取值 0～255，例如 192.168.1.1。",
        "example": "192.168.1.1 的四段分别占 1 个字节，总计 4 字节。",
        "pitfall": "IPv6 是 128 位，不要与 IPv4 的 32 位混淆。",
    },
    "J059": {
        "answer": "RAM 是可读写的易失性随机存取存储器；ROM 通常用于保存断电后仍需保留的固件或数据",
        "explanation": "程序运行时的代码和数据主要放在 RAM 中，断电后内容消失；ROM 或闪存类非易失性存储断电后仍能保留内容，通常用于固件。",
        "example": "运行程序占用的是 RAM；主板固件通常保存在非易失性存储中。",
        "pitfall": "ROM 的实际实现可能允许特定方式重写，但它与 RAM 在用途和易失性上仍不同。",
    },
    "J063": {
        "answer": "前面的表达式已能确定结果时，后面的表达式不再执行",
        "explanation": "A && B 在 A 为假时不会计算 B；A || B 在 A 为真时不会计算 B。它可用于先验证下标或指针合法，再访问数据。",
        "example": "i < n && a[i] == target：若 i>=n，就不会访问 a[i]。",
        "pitfall": "逻辑或是 ||；若右侧包含自增、函数调用等副作用，要意识到它可能根本不会执行。",
    },
    "J064": {
        "answer": "使用 getline(cin, s);",
        "explanation": "operator>> 读取字符串时遇到空白就停止，而 getline 会读取整行内容，直到换行符为止。",
        "example": "string s; getline(cin, s); // 可读入 hello world",
        "pitfall": "若前面使用过 cin >> n，应先清理输入缓冲区中残留的换行符。",
    },
    "J065": {
        "answer": "初始化 → 条件判断 → 循环体 → 更新表达式 → 再次条件判断",
        "explanation": "for(A; B; C){D;} 先执行一次 A；每轮先判断 B，为真才执行 D，然后执行 C，再回到 B。若 B 第一次就为假，循环体一次也不执行。",
        "example": "for(int i=0; i<3; ++i) cout<<i; 依次输出 0、1、2。",
        "pitfall": "更新表达式在循环体之后执行；continue 会直接进入本轮的更新表达式。",
    },
    "J067": {
        "promptText": "在 C++ 中，数组下标从几开始？",
        "answer": "从 0 开始",
        "explanation": "长度为 n 的数组 a 有 n 个元素，合法下标是 0～n-1。若想按 1～n 使用，通常多开一个元素并主动空出 0 号位置。",
        "example": "int a[10]; 的合法下标是 a[0] 到 a[9]。",
        "pitfall": "a[n] 不是第 n 个元素，而是越过长度为 n 的数组末尾。",
    },
    "J071": {
        "answer": "多维数组是以数组为元素的数组；3 行 4 列可定义为 int a[3][4];",
        "explanation": "a[3][4] 含 12 个 int，C++ 按行连续存储。行下标为 0～2，列下标为 0～3。",
        "example": "a[1][2] 表示第 2 行第 3 列的元素。",
        "pitfall": "两个维度都从 0 开始；较大的局部数组可能导致栈空间不足。",
    },
    "J075": {
        "answer": "每次比较中间元素并排除一半区间；前提是序列有序",
        "explanation": "二分查找维护一个可能包含目标的有序区间，比较中点后只保留左半或右半，因此时间复杂度为 O(log n)。",
        "example": "在 1,3,5,7,9 中找 7，先比较 5，再只搜索右半区。",
        "pitfall": "无序序列不能直接二分；中点可写 l+(r-l)/2，并统一好闭区间或半开区间写法。",
    },
    "J078": {
        "answer": "预处理前缀和后，可在 O(1) 时间求任意连续区间的和",
        "explanation": "令 s[i]=a[1]+…+a[i]，则区间 [L,R] 的和为 s[R]-s[L-1]。预处理一次需要 O(n)，适合数组不变但查询很多的场景。",
        "example": "a={2,4,1,3} 时 s={0,2,6,7,10}，[2,4] 的和是 10-2=8。",
        "pitfall": "通常令 s[0]=0；数据和可能超过 int，应根据范围使用 long long。",
    },
    "J080": {
        "answer": "包含 <algorithm> 后调用 sort(a, a+n);",
        "explanation": "std::sort 对左闭右开区间 [first,last) 排序，默认升序，复杂度为 O(n log n)。vector 可写 sort(v.begin(),v.end())。",
        "example": "int a[3]={3,1,2}; sort(a,a+3); // 得到 1,2,3",
        "pitfall": "第二个迭代器指向末尾之后；自定义比较函数必须满足严格弱序。",
    },
    "J081": {
        "answer": "元素之间是一对一的线性关系，除首尾外每个元素各有一个直接前驱和后继",
        "explanation": "数组和链表都是线性表。数组连续存储并支持 O(1) 随机访问；链表通过链接组织元素，定位较慢但已知位置后的插删方便。",
        "example": "序列 A→B→C 中，B 的直接前驱是 A，直接后继是 C。",
        "pitfall": "线性表描述的是逻辑关系，不等于一定使用连续内存。",
    },
    "J082": {
        "answer": "后进先出（LIFO）；常用 push、pop、top、empty",
        "explanation": "栈只操作栈顶，最后压入的元素最先弹出，常用于括号匹配、表达式求值、DFS 和函数调用。",
        "example": "stack<int> s; s.push(1); s.push(2); cout<<s.top(); // 2",
        "pitfall": "pop 只删除不返回元素；调用 top 或 pop 前先确认非空。",
    },
    "J083": {
        "answer": "先进先出（FIFO）；常用 push、pop、front、empty",
        "explanation": "队列从队尾加入、队头取出，最早入队的元素最先出队，是 BFS 按层访问的核心容器。",
        "example": "queue<int> q; q.push(1); q.push(2); cout<<q.front(); // 1",
        "pitfall": "pop 只删除队首；调用 front 或 pop 前先确认队列非空。",
    },
    "J084": {
        "answer": "已找到位置时，插入或删除只需修改链接，不必搬移后续元素",
        "explanation": "链表节点无需连续存储，已知目标节点时插删可在 O(1) 完成；数组中间插删通常要移动 O(n) 个元素。链表查找第 k 个元素仍需 O(n)。",
        "example": "在节点 p 后插入 q：q.next=p.next; p.next=q;。",
        "pitfall": "“任意位置 O(1)”的前提是位置已经找到，否则定位仍可能耗时 O(n)。",
    },
    "J085": {
        "answer": "二叉树是每个节点最多有两个子节点的树形结构",
        "explanation": "两个子节点分别称为左孩子和右孩子。二叉树可用于表示搜索树、堆、表达式树等结构。",
        "example": "struct Node { int value; Node *left, *right; };",
        "pitfall": "“最多两个”包含 0 个、1 个或 2 个子节点，不是每个节点都必须有两个。",
    },
    "J086": {
        "answer": "通常指从根到最深叶子的最长路径长度；需先约定按节点数还是边数计",
        "explanation": "若根在第 1 层，则树高等于最深节点的层数；若按边数计，单个根节点高度为 0。高度决定递归深度及许多树操作的复杂度。",
        "example": "根→孩子→孙子共 3 层：按层数高度为 3，按边数高度为 2。",
        "pitfall": "不同教材对高度和深度的起点约定可能不同，答题时必须说明口径。",
    },
    "J087": {
        "promptText": "如何用邻接矩阵表示一个有 n 个节点的无向图？",
        "answer": "使用 n×n 数组 g；有边 (i,j) 时令 g[i][j]=g[j][i]=1",
        "explanation": "无向图的邻接矩阵关于主对角线对称。判断两点是否有边是 O(1)，但空间需要 O(n^2)，适合点数较少或较稠密的图。",
        "example": "边 (2,5) 对应 g[2][5]=1 和 g[5][2]=1。",
        "pitfall": "稀疏大图使用邻接矩阵会浪费大量空间，通常改用邻接表。",
    },
    "J088": {
        "answer": "vector 是可动态扩容、又支持下标随机访问的连续数组容器",
        "explanation": "vector 支持 size、push_back 和 O(1) 下标访问，尾部插入均摊 O(1)。容量不足时会重新分配并搬移元素。",
        "example": "vector<int> v={1,2}; v.push_back(3);",
        "pitfall": "clear 令 size 变为 0，但通常不归还 capacity；扩容还可能使迭代器和引用失效。",
    },
    "J089": {
        "answer": "字符串长度是包含的字符个数；使用 s.size() 或 s.length()",
        "explanation": "对 std::string，size 和 length 含义相同，返回 size_t，且不把内部结尾空字符算进长度。",
        "example": "string s=\"NOI\"; cout<<s.size(); // 3",
        "pitfall": "循环下标类型要注意与 size_t 的有符号/无符号比较；UTF-8 中文的字节数不等于汉字个数。",
    },
    "J090": {
        "answer": "按优先级取出元素的容器适配器；默认队首是最大值",
        "explanation": "priority_queue 通常用堆实现，push 和 pop 为 O(log n)，top 为 O(1)。需要最小值优先时可指定 greater<int>。",
        "example": "priority_queue<int, vector<int>, greater<int>> pq; // 小根堆",
        "pitfall": "它不是普通 FIFO 队列；pop 前应确认非空。",
    },
    "J092": {
        "answer": "使用欧几里得算法：反复令 (a,b) 变为 (b,a%b)，直到 b=0",
        "explanation": "gcd(a,b)=gcd(b,a%b)，余数不断变小，因此算法很快，复杂度为 O(log min(a,b))。",
        "example": "int gcd(int a,int b){ return b==0 ? abs(a) : gcd(b,a%b); }",
        "pitfall": "求 lcm 时应先除后乘：a/gcd(a,b)*b，以降低溢出风险。",
    },
    "J093": {
        "answer": "不断除以 2 记录余数，最后将余数逆序排列",
        "explanation": "每次 n%2 得到当前最低二进制位，再令 n/=2；因为先得到低位，所以输出时要反转记录。",
        "example": "13 依次得到余数 1、0、1、1，逆序后为 1101。",
        "pitfall": "n=0 时应直接输出 0；负数的符号需单独处理。",
    },
    "J096": {
        "promptText": "逻辑运算中，P∧Q（与）和 P∨Q（或）的真值规则是什么？",
        "answer": "与：P、Q 都真才真；或：P、Q 至少一个为真就真",
        "explanation": "P∧Q 只有真/真组合结果为真；P∨Q 只有假/假组合结果为假。逻辑非会翻转真假，异或则在两者不同时为真。",
        "example": "P=true、Q=false 时，P∧Q=false，P∨Q=true。",
        "pitfall": "数学符号 ∧/∨ 对应 C++ 的 &&/||；不要与按位 &/| 混淆。",
    },
    "J098": {
        "answer": "交集包含两个集合共有的元素；并集包含至少属于其中一个集合的元素",
        "explanation": "交集记作 A∩B，并集记作 A∪B。集合不含重复元素，因此并集列出元素时也只保留一份。",
        "example": "A={1,2,3}, B={3,4}，则 A∩B={3}，A∪B={1,2,3,4}。",
        "pitfall": "交集与并集不要混淆；集合中的重复写法不会增加元素个数。",
    },
    "J099": {
        "answer": "n>0 && (n & (n-1)) == 0",
        "explanation": "正的 2 的幂在二进制中只有一个 1；减 1 后该位变 0、右侧全变 1，与原数按位与得到 0。",
        "example": "8 是 1000₂，7 是 0111₂，8&7=0，所以 8 是 2 的幂。",
        "pitfall": "必须先排除 n<=0；0 也满足 0&(0-1)==0，但 0 不是 2 的幂。",
    },
    "J100": {
        "answer": "排列考虑顺序，组合不考虑顺序",
        "explanation": "从 n 个不同元素中选 k 个，排列数为 n!/(n-k)!，组合数为 n!/[k!(n-k)!]。同一组元素的不同次序属于不同排列，但属于同一个组合。",
        "example": "从 A、B、C 选 2 个：AB 与 BA 是两个排列，但对应同一个组合 {A,B}。",
        "pitfall": "先判断题目是否区分顺序，再决定使用排列还是组合。",
    },
    "J104": {
        "answer": ".cpp",
        "explanation": "C++ 源文件通常使用 .cpp 后缀，并交给 g++ 等 C++ 编译器编译。Linux 文件名区分大小写。",
        "example": "main.cpp 可用 g++ -O2 main.cpp -o main 编译。",
        "pitfall": "后缀是小写 .cpp；-o 用于指定输出文件名，不是优化选项 -O2。",
    },
    "J111": {
        "answer": "通常为 -2147483648 到 2147483647，约 ±2×10^9",
        "explanation": "主流竞赛环境中的 int 是 32 位有符号整数。超过范围会发生整数溢出，因此大范围求和、乘法常需 long long。",
        "example": "1LL*1000000000*1000000000 会先提升为 long long。",
        "pitfall": "即使最终变量是 long long，中间的 int*int 也可能先溢出，应提前用 1LL 转换。",
    },
    "J112": {
        "answer": "printf(\"%.2f\",x)，或 cout<<fixed<<setprecision(2)<<x",
        "explanation": "printf 的 %.2f 表示小数点后保留两位；cout 方案需要包含 <iomanip>，fixed 令 setprecision(2) 表示小数位数。输出会按规则四舍五入。",
        "example": "double x=3.14159; cout<<fixed<<setprecision(2)<<x; // 3.14",
        "pitfall": "格式化输出只改变显示，不会把变量本身精确地改成两位小数。",
    },
    "J115": {
        "answer": "const double PI = 3.14159;",
        "explanation": "const 令对象初始化后不能再被修改，并保留明确的类型检查。相比 #define 文本替换，const 或 constexpr 更符合现代 C++。",
        "example": "constexpr double PI = 3.141592653589793;",
        "pitfall": "PI 是大写字母 I，不是小写字母 l；常量必须在使用前完成初始化。",
    },
    "J117": {
        "answer": "a++ 先产生原值再自增；++a 先自增再产生新值",
        "explanation": "单独作为语句时两者都让 a 加 1，但参与更大表达式时结果不同。对于内置整数，现代编译器通常能同样优化。",
        "example": "a=5; b=a++; 后 b=5、a=6；a=5; b=++a; 后 b=6、a=6。",
        "pitfall": "不要在一个复杂表达式中多次修改同一变量，代码难读且某些写法会产生未定义或不易预期的行为。",
    },
    "J119": {
        "answer": "全局变量默认零初始化；未显式初始化的普通局部自动变量值不确定",
        "explanation": "全局变量具有静态存储期，程序开始时先完成零初始化；局部自动变量进入作用域时创建，若未初始化就读取，会产生未定义行为。",
        "example": "全局 int g; 初值为 0；函数内 int x; 必须先赋值再读取。",
        "pitfall": "局部变量不是可靠的“随机数”，而是值不确定；任何读取都可能让程序行为错误。",
    },
    "J125": {
        "answer": "n*(n+1)/2",
        "explanation": "1 到 n 是等差数列，用首尾配对可得和为 n(n+1)/2，因此无需循环，时间复杂度为 O(1)。",
        "example": "n=100 时，和为 100*101/2=5050。",
        "pitfall": "n 较大时 n*(n+1) 可能先发生 int 溢出，应使用 1LL*n*(n+1)/2。",
    },
    "J130": {
        "answer": "使用欧几里得算法（辗转相除法）",
        "explanation": "反复利用 gcd(a,b)=gcd(b,a%b)，直到 b=0，此时 |a| 就是最大公约数。余数快速变小，复杂度为 O(log min(a,b))。",
        "example": "int gcd(int a,int b){ return b==0 ? abs(a) : gcd(b,a%b); }",
        "pitfall": "C++17 可使用 std::gcd；若还要求最小公倍数，应先除后乘以降低溢出风险。",
    },
    "J131": {
        "answer": "vector<int> v;",
        "explanation": "std::vector 是连续存储的动态数组，支持下标访问、size 和 push_back；容量不足时会自动扩容。需包含 <vector>。",
        "example": "vector<int> v; v.push_back(10); cout<<v[0];",
        "pitfall": "不要漏写元素类型 <int>；扩容可能使已有迭代器、引用和指针失效。",
    },
    "J134": {
        "promptText": "字符串 s=\"ABC\"，s.length() 返回多少？",
        "answer": "3",
        "explanation": "std::string::length() 与 size() 都返回字符串包含的字符单元数。字符串 \"ABC\" 含 A、B、C 三个字符。",
        "example": "string s=\"ABC\"; cout<<s.length(); // 3",
        "pitfall": "std::string 的长度不包含结尾空字符；UTF-8 中文的 size() 返回字节数，不一定是汉字数。",
    },
    "J136": {
        "promptText": "图的邻接矩阵中，a[i][j]=1 表示什么？",
        "answer": "表示顶点 i 到顶点 j 之间存在一条边",
        "explanation": "邻接矩阵用二维数组记录顶点间的连接关系。无向图中 a[i][j] 与 a[j][i] 相同；有向图中二者分别表示两个方向。",
        "example": "若 a[2][4]=1，则图中存在从顶点 2 指向顶点 4 的边。",
        "pitfall": "带权图可能用 a[i][j] 存边权；没有边时的哨兵值要按实现约定。",
    },
    "J138": {
        "answer": "循环 pop 直到 empty，或与一个空 queue 交换",
        "explanation": "std::queue 没有 clear 成员函数。可以 while(!q.empty()) q.pop();，也可创建空队列并 swap，以便同时释放原容器持有的空间。",
        "example": "queue<int> empty; q.swap(empty);",
        "pitfall": "不能在队列为空时继续 pop；重新赋值 queue<int>() 也能清空。",
    },
    "J140": {
        "answer": "x % 2 != 0",
        "explanation": "奇数不能被 2 整除，所以余数不为 0。写成 !=0 对负奇数也成立；也可使用 (x&1)!=0 判断整数最低位。",
        "example": "x=-3 时，x%2 为 -1，但 x%2!=0 仍正确判断为奇数。",
        "pitfall": "不要只写 x%2==1，因为负奇数在 C++ 中余数通常是 -1。",
    },
    "J143": {
        "answer": "欧几里得算法，也叫辗转相除法",
        "explanation": "利用 gcd(a,b)=gcd(b,a%b) 反复取余；当 b=0 时返回 |a|。算法复杂度为 O(log min(a,b))。",
        "example": "gcd(48,18)=gcd(18,12)=gcd(12,6)=6。",
        "pitfall": "递归基例应是 b==0，不是某个固定非零数字。",
    },
    "J147": {
        "promptText": "集合 A={1,2,3}、B={3,4,5}，则 A∩B 是多少？",
        "answer": "{3}",
        "explanation": "交集只保留同时属于 A 和 B 的元素。两个集合唯一共有的元素是 3，因此 A∩B={3}。",
        "example": "对应的并集 A∪B={1,2,3,4,5}。",
        "pitfall": "∩ 表示交集，∪ 表示并集，不要混淆。",
    },
    "J149": {
        "answer": "不重复、不遗漏地遍历所有可能情况，并逐一验证是否满足条件",
        "explanation": "枚举法先明确枚举对象、范围和判定条件，再检查每个候选解。它实现直接但复杂度取决于候选数量，范围大时需要剪枝或更优算法。",
        "example": "寻找 1～100 中能被 3 整除的数，可以逐个 i 判断 i%3==0。",
        "pitfall": "枚举边界最容易出现重复、遗漏或差一错误；先根据数据范围估算是否会超时。",
    },
    "J151": {
        "answer": "前提是查找区间具有有序性或单调性；每次排除一半，复杂度 O(log n)",
        "explanation": "普通二分查找比较目标与中点，只保留仍可能含答案的一半区间。二分答案则依赖 check(x) 的真假具有单调分界。",
        "example": "在升序数组中，若 a[mid]<target，就令 l=mid+1 搜索右半区。",
        "pitfall": "统一使用 [l,r] 或 [l,r) 模板；中点写 l+(r-l)/2 可避免加法溢出。",
    },
    "J153": {
        "answer": "每一步选择当前看来最优的方案，并希望由此构成全局最优解",
        "explanation": "贪心算法只保留当前决策，不回头修改。只有问题具备贪心选择性质和最优子结构时才正确，因此关键在于证明，而不是看到“最大/最小”就套贪心。",
        "example": "区间调度中，每次选择结束时间最早且不冲突的区间，可获得最多互不重叠区间。",
        "pitfall": "局部最优不一定推出全局最优；无法证明时应寻找反例或考虑动态规划。",
    },
    "J157": {
        "answer": "从 2 开始，把每个未标记质数的倍数标记为合数，剩余未标记数就是质数",
        "explanation": "枚举质数 p 时可从 p² 开始标记，因为更小倍数已被更小质数处理。筛到 n 的总复杂度约为 O(n log log n)。",
        "example": "筛到 p=5 时，从 25 开始标记 25、30、35……。",
        "pitfall": "0 和 1 不是质数；p*p 可能溢出，应使用足够大的整数类型或 p<=n/p。",
    },
    "J160": {
        "promptText": "如何用二分思想计算 a 的 n 次方（快速幂）？",
        "answer": "把指数按二进制拆分，反复平方底数并在对应位为 1 时乘入答案，复杂度 O(log n)",
        "explanation": "每轮根据 n 的最低位决定是否把当前 a 乘入结果，然后令 a=a*a、n>>=1。指数每次减半，所以只需对数轮。取模问题中每次乘法都要取模。",
        "example": "long long qpow(long long a,long long n){ long long r=1; while(n){ if(n&1) r*=a; a*=a; n>>=1; } return r; }",
        "pitfall": "乘法可能溢出；带模时应使用足够宽的类型并在每次乘法后取模。",
    },
    "J161": {
        "answer": "沿一条分支尽量深入，走不下去时回溯，再探索其他分支",
        "explanation": "DFS 可用递归或显式栈实现。图搜索中要用 visited 防止重复访问；回溯题还常需在递归返回前恢复选择现场。",
        "example": "遍历图时访问 u 后，依次对每个尚未访问的邻居 v 调用 dfs(v)。",
        "pitfall": "图中不做访问标记可能陷入环；递归过深还可能导致栈溢出。",
    },
    "J162": {
        "answer": "用队列按距离从近到远逐层访问；在无权图中第一次到达目标时就是最短路",
        "explanation": "BFS 先访问起点，再访问所有距离为 1、2、3……的节点。邻居入队时记录 dist[v]=dist[u]+1，因此第一次访问到 v 的边数最少。",
        "example": "q.push(start); dist[start]=0；每次弹出 u，把未访问邻居 v 入队并设 dist[v]=dist[u]+1。",
        "pitfall": "仅适用于无权图或所有边权相同的最短路；visited 应在入队时标记，避免重复入队。",
    },
    "J164": {
        "promptText": "竞赛中如何实现结构体排序（Struct Sorting）？",
        "answer": "为 std::sort 提供比较函数，或为结构体定义 operator<",
        "explanation": "比较函数 cmp(a,b) 返回 true 表示 a 应排在 b 前面，可依次比较总分、单科分数、编号等多个关键字。它必须满足严格弱序。",
        "example": "bool cmp(const Node&a,const Node&b){ if(a.score!=b.score)return a.score>b.score; return a.id<b.id; }",
        "pitfall": "相等时不能返回 true，比较中应使用 < 或 >，不要用 <= 或 >= 破坏严格弱序。",
    },
    "J167": {
        "answer": "维护一个连续区间的左右端点，随着新元素进入或旧元素移出动态更新窗口",
        "explanation": "滑动窗口利用约束的单调性，让左右指针都只向前移动，从而把许多 O(n^2) 的连续区间枚举降为 O(n)。",
        "example": "正数数组求和不超过 S 的最长区间：右端加入元素，和超过 S 时不断右移左端并减去移出的值。",
        "pitfall": "可变窗口通常要求约束随端点移动具有单调性；含负数时“和超过就缩小”未必正确。",
    },
    "J169": {
        "answer": "若 n<2 则不是质数；否则试除 2～√n，没有因数就是质数",
        "explanation": "合数的因数成对出现，其中至少一个不超过 √n，所以不必检查到 n-1，复杂度为 O(√n)。",
        "example": "bool prime(int n){ if(n<2)return false; for(int i=2;i<=n/i;++i) if(n%i==0)return false; return true; }",
        "pitfall": "1 不是质数；使用 i<=n/i 可以避免 i*i 的整数溢出。",
    },
    "J172": {
        "promptText": "如何把 N 进制数转换为十进制？",
        "answer": "从高位到低位扫描，反复执行 ans=ans*N+当前数位",
        "explanation": "这是按权展开的秦九韶写法，不必显式计算 N 的幂。每读入一位，就先把已有结果左移一个 N 进制位，再加当前数位。",
        "example": "二进制 101：((0*2+1)*2+0)*2+1=5。",
        "pitfall": "字符数位要先转换成数值并检查小于 N；结果可能超出 long long。",
    },
    "J173": {
        "answer": "把大问题分成若干较小且同类的子问题，分别解决后合并结果",
        "explanation": "分治通常包含分解、求解、合并三步，常用递归实现。归并排序把数组分半排序，再在线性时间合并。",
        "example": "归并排序：sort(l,mid)，sort(mid+1,r)，最后 merge(l,mid,r)。",
        "pitfall": "必须有足够小的递归边界；还要把合并步骤的复杂度计入总复杂度。",
    },
    "J175": {
        "answer": "从 1 到 N 依次累乘，时间复杂度 O(n)，并根据范围处理溢出或取模",
        "explanation": "N!=1×2×…×N。阶乘增长很快：13! 已超过 32 位 int，21! 超过 64 位有符号整数，较大 N 需要取模或高精度。",
        "example": "long long ans=1; for(int i=2;i<=n;++i) ans*=i;",
        "pitfall": "0!=1；不能因为结果变量是 long long 就忽略中间乘法的范围。",
    },
    "J176": {
        "answer": "朴素递归有大量重复计算，可用记忆化或递推把时间降为 O(n)",
        "explanation": "f(n)=f(n-1)+f(n-2) 的递归树会反复计算相同状态。保存已算结果，或从 f(0)、f(1) 向后推，就只需计算每个状态一次。",
        "example": "f[0]=0; f[1]=1; for(int i=2;i<=n;++i) f[i]=f[i-1]+f[i-2];",
        "pitfall": "从 F(93) 开始会超过有符号 long long；题目若取模，要在每步相加后取模。",
    },
    "J177": {
        "answer": "先把 n-1 个盘从起点移到辅助柱，再把最大盘移到目标柱，最后把 n-1 个盘移到目标柱",
        "explanation": "递归把规模 n 的问题化为两次规模 n-1 的问题和一次最大盘移动。最少移动次数满足 T(n)=2T(n-1)+1=2^n-1。",
        "example": "n=2：小盘 A→B，大盘 A→C，小盘 B→C，共 3 步。",
        "pitfall": "盘子移动过程中任何时刻都不能把大盘放在小盘上；步数随 n 指数增长。",
    },
    "J181": {
        "answer": "遍历所有可能候选，并检查哪些满足题目条件",
        "explanation": "简单枚举先确定候选范围，再用循环逐个验证。它可靠易写，但候选总数过大时会超时，需要缩小范围、剪枝或改用更高效算法。",
        "example": "for(int i=1;i<=100;++i) if(i%3==0) ++ans;",
        "pitfall": "先估算枚举次数，并仔细处理上下界，避免遗漏和重复。",
    },
    "J186": {
        "answer": "sort(first,last)；可选第三个比较函数 cmp，区间为左闭右开 [first,last)",
        "explanation": "std::sort 位于 <algorithm>，默认升序，复杂度为 O(n log n)。数组 a 的前 n 个元素写 sort(a,a+n)，vector 写 sort(v.begin(),v.end())。",
        "example": "sort(a,a+n); sort(v.begin(),v.end(),greater<int>());",
        "pitfall": "last 指向最后一个元素之后；cmp 必须满足严格弱序，不能用 <= 代替 <。",
    },
    "J188": {
        "answer": "将问题分成较小的同类子问题，递归求解，再合并子问题结果",
        "explanation": "分治的三步是分解、解决和合并。典型例子有归并排序、快速排序和二分查找。",
        "example": "归并排序先分别排好左右两半，再用双指针合并两个有序区间。",
        "pitfall": "递归必须有终止条件，并且子问题规模要真正缩小。",
    },
    "J189": {
        "answer": "预处理 s[i]=前 i 个元素之和，使区间 [L,R] 的和可用 s[R]-s[L-1] 求出",
        "explanation": "前缀和预处理 O(n)，每次区间查询 O(1)，适合数组不修改而区间求和很多的场景。",
        "example": "for(int i=1;i<=n;++i) s[i]=s[i-1]+a[i]; long long sum=s[R]-s[L-1];",
        "pitfall": "保留 s[0]=0；前缀总和可能超出 int。",
    },
    "J190": {
        "answer": "把一次区间加减转化为差分数组上的两个端点修改",
        "explanation": "令 d[i]=a[i]-a[i-1]。区间 [L,R] 加 v 只需 d[L]+=v、d[R+1]-=v，全部修改后对 d 求前缀和还原数组。",
        "example": "d[L]+=v; d[R+1]-=v;",
        "pitfall": "需要给 R+1 预留空间；它适合批量修改后统一还原，不直接支持任意时刻的复杂在线查询。",
    },
    "J191": {
        "answer": "使用欧几里得算法：gcd(a,b)=gcd(b,a%b)",
        "explanation": "反复取余直到 b=0，返回 |a|。每次余数都会变小，因此复杂度为 O(log min(a,b))。",
        "example": "int gcd(int a,int b){ return b==0 ? abs(a) : gcd(b,a%b); }",
        "pitfall": "C++17 可使用 std::gcd；求最小公倍数时先除后乘以避免溢出。",
    },
    "J194": {
        "answer": "当答案范围有序，且可行性 check(x) 随 x 具有单调分界时",
        "explanation": "如果直接求最优值困难，但能快速判断某个候选 x 是否可行，就可在答案范围上二分，每次根据 check(mid) 排除一半。",
        "example": "若长度 L 越小越容易满足要求，可二分寻找最大的可行 L。",
        "pitfall": "使用前必须证明 check 的单调性，并分清是找第一个可行、最后一个可行、最小值最大化还是最大值最小化。",
    },
    "J197": {
        "answer": "用两个按特定规律移动的下标或指针共同扫描数据",
        "explanation": "双指针可以同向形成滑动窗口，也可从两端相向移动。利用有序性或单调性，可让每个指针只移动 O(n) 次，把部分 O(n^2) 枚举降为 O(n)。",
        "example": "升序数组找两数和：和小于目标就右移左指针，和大于目标就左移右指针。",
        "pitfall": "指针移动规则必须有单调性依据，否则可能漏解。",
    },
    "J198": {
        "answer": "用数组或字符串按位模拟竖式加法，从低位相加并处理进位",
        "explanation": "超出 long long 的整数可把每一位单独保存。第 i 位计算 sum=a[i]+b[i]+carry，当前位为 sum%10，进位为 sum/10。",
        "example": "999+8：个位 9+8=17 写 7 进 1，十位和百位继续处理，得到 1007。",
        "pitfall": "两个数长度不同要把缺少的高位按 0 处理，最后仍有进位时要补上一位。",
    },
    "J199": {
        "answer": "把指数按二进制拆分，反复平方底数，复杂度 O(log b)",
        "explanation": "每轮若 b 的最低位为 1 就把当前 a 乘入结果，然后令 a=a*a、b>>=1。取模快速幂中每次乘法都要取模。",
        "example": "long long qpow(long long a,long long b,long long m){ long long r=1%m; while(b){ if(b&1)r=r*a%m; a=a*a%m; b>>=1; } return r; }",
        "pitfall": "a*a 仍可能在取模前溢出；模数很大时需要更宽类型或安全乘法。",
    },
    "J202": {
        "answer": "用数组标记已出列者，或用队列/链表循环报数并删除第 M 人",
        "explanation": "N 人成环，从当前位置循环跳过已出列者，每数到 M 就删除并从下一人重新计数。直接模拟适合 N、M 较小的情况。",
        "example": "N=5、M=2 时，按规则依次出列 2、4、1、5、3。",
        "pitfall": "删除后要从下一位继续，并正确处理下标绕回开头。",
    },
    "J203": {
        "answer": "值域小时用计数数组；否则排序后统计连续相同值的最长段",
        "explanation": "计数法需 O(n+K) 时间和 O(K) 空间；排序法需 O(n log n) 排序和 O(n) 扫描。众数若并列，要按题意决定输出规则。",
        "example": "1,2,2,3,3 中 2 和 3 都出现两次，二者并列为众数。",
        "pitfall": "摩尔投票只保证寻找“出现次数超过一半”的候选，不能直接解决一般众数问题。",
    },
    "J204": {
        "answer": "枚举主串中的每个可能起点，再逐位比较模式串",
        "explanation": "主串长度 N、模式长度 M 时，暴力匹配最坏复杂度为 O(NM)。数据较小时可直接使用 std::string::find。",
        "example": "在 abcabc 中查找 cab，从起点 0、1 失败，到起点 2 匹配成功。",
        "pitfall": "起点只需枚举到 N-M；数据很大时应考虑 KMP 等线性算法。",
    },
    "J206": {
        "answer": "用深度优先搜索尝试选择；不合适或递归返回时撤销选择，再尝试下一种",
        "explanation": "回溯的典型结构是“做选择 → 递归 → 撤销选择”，用于排列组合、八皇后和迷宫等搜索问题。剪枝可提前排除不可能分支。",
        "example": "生成排列时先标记元素已使用，递归返回后再取消标记。",
        "pitfall": "忘记恢复现场会影响其他分支；只做 DFS 而没有撤销不一定是回溯。",
    },
    "J208": {
        "answer": "把两个已经有序的子序列线性合并成一个有序序列",
        "explanation": "归并排序先递归排好左右两半，再用两个指针每次取较小元素放入临时数组。总时间复杂度稳定为 O(n log n)，额外空间 O(n)，并且稳定。",
        "example": "合并 [1,4,7] 与 [2,3,8]，依次取 1、2、3、4、7、8。",
        "pitfall": "一侧用完后要把另一侧剩余元素全部复制；相等时先取左侧可保持稳定性。",
    },
    "J210": {
        "answer": "二进制浮点数不能精确表示许多十进制小数，运算后会有舍入误差",
        "explanation": "因此不应直接用 a==b 判断计算得到的浮点数相等，常比较 |a-b|<eps；eps 要结合数据量级和题目允许误差选择。",
        "example": "0.1+0.2 在二进制浮点中通常不与 0.3 完全相等，可判断 fabs(a-b)<1e-9。",
        "pitfall": "eps 不是固定万能值；数值很大时可能需要相对误差。",
    },
    "J211": {
        "promptText": "进制转换中的秦九韶算法思想是什么？",
        "answer": "从高位到低位扫描，每次令 res = res*K + 当前数位",
        "explanation": "K 进制数 d1d2…dn 的值可嵌套写成 (((d1)*K+d2)*K+…)+dn，因此无需单独计算 K 的各次幂。",
        "example": "十六进制 1F：res=0*16+1=1，再得 res=1*16+15=31。",
        "pitfall": "要先把字符 '0'～'9'、'A'～'Z' 正确转换为数位值，并检查数位是否小于 K。",
    },
    "J212": {
        "answer": "01 背包每件物品最多一次；完全背包每种物品可使用无限次",
        "explanation": "一维优化时，01 背包容量必须逆序遍历，避免同一轮重复使用当前物品；完全背包容量正序遍历，允许由本轮已更新状态继续转移。",
        "example": "完全背包：for(int j=w[i]; j<=W; ++j) dp[j]=max(dp[j],dp[j-w[i]]+v[i]);",
        "pitfall": "容量遍历方向写反会把 01 背包和完全背包的含义互换。",
    },
    "J216": {
        "answer": "枚举每个约数 i，并给 i、2i、3i… 的约数个数各加 1",
        "explanation": "每个 i 都是其倍数的约数，所以 for i=1..N，再遍历 j=i; j<=N; j+=i 执行 cnt[j]++。总复杂度约为 O(N log N)。",
        "example": "for(int i=1;i<=N;++i) for(int j=i;j<=N;j+=i) ++cnt[j];",
        "pitfall": "数组至少开到 N；若只查询少量很大的数，逐个试除可能更合适。",
    },
    "J220": {
        "answer": "数值前面多余的 0 通常要去掉；固定宽度格式要求的 0 则要按题意保留",
        "explanation": "大整数或进制转换输出时，可从最高非零位开始；时间、编号等固定宽度输出可用 setw 和 setfill('0') 补零。",
        "example": "cout<<setfill('0')<<setw(5)<<42; 输出 00042。",
        "pitfall": "数值 0 本身必须至少输出一个 0；不要把题目要求保留的格式零误删。",
    },
    "J223": {
        "answer": "按数据范围选择类型，并让中间运算在足够宽的类型中进行",
        "explanation": "int 通常约 ±2×10^9，long long 约 ±9×10^18。int 相乘可能在赋给 long long 前就溢出，因此可写 1LL*a*b；仍超范围时需高精度或安全乘法。",
        "example": "long long value = 1LL * a * b;",
        "pitfall": "强转必须发生在可能溢出的运算之前；取模不能挽救已经发生的溢出。",
    },
    "J224": {
        "promptText": "什么是位（bit）和字节（Byte）？它们的关系是什么？",
        "answer": "位表示一个二进制 0 或 1；字节是常用存储单位，1 Byte = 8 bits",
        "explanation": "bit 是信息存储的基本二进制单位，Byte 由 8 个 bit 组成。按二进制容量口径，1 KiB=1024 B、1 MiB=1024 KiB。一个 int 通常占 4 字节。",
        "example": "128 MiB 约为 134217728 字节，理论上可容纳约 3355 万个 4 字节 int，实际还要给程序其他数据留空间。",
        "pitfall": "b 常表示 bit，B 表示 Byte；十进制 GB 与二进制 GiB 的换算口径也不要混淆。",
    },
    "J226": {
        "promptText": "什么是 ASCII 码？'A' 和 'a' 的码值分别是多少？",
        "answer": "ASCII 是美国信息交换标准代码；'A'=65，'a'=97",
        "explanation": "标准 ASCII 用 7 位定义 128 个字符。数字和英文字母连续编码，因此可用字符减法完成常见转换。",
        "example": "ch-'A' 可把大写字母 A～Z 映射为 0～25。",
        "pitfall": "大小写字母码值相差 32，但更推荐使用标准库字符转换函数处理通用场景。",
    },
    "J227": {
        "promptText": "逻辑与（AND）、或（OR）、非（NOT）的规则是什么？",
        "answer": "与：全真才真；或：至少一个真就真；非：真变假、假变真",
        "explanation": "C++ 中逻辑与、或、非分别是 &&、||、!。&& 与 || 具有短路求值特性。",
        "example": "true&&false 为 false，true||false 为 true，!true 为 false。",
        "pitfall": "逻辑运算符 &&、||、! 不要与按位运算符 &、|、~ 混淆。",
    },
    "J230": {
        "answer": "IP 地址是网络层用于标识接口并进行分组寻址和路由的地址",
        "explanation": "IPv4 地址长 32 位，通常写成四段点分十进制；IPv6 地址长 128 位。路由器根据目标 IP 把数据包转发到相应网络。",
        "example": "192.168.1.10 是一个 IPv4 地址，每段范围为 0～255。",
        "pitfall": "IP 地址不一定永久唯一绑定一台设备，也不等同于 MAC 地址或域名。",
    },
    "J231": {
        "answer": ".exe 是常见 Windows 可执行文件，.cpp 是 C++ 源文件，.o 是目标文件",
        "explanation": "编译器先把源代码编译成目标文件，再由链接器把一个或多个目标文件与库链接为可执行程序。Linux 可执行文件通常不需要 .exe 后缀。",
        "example": "g++ -c main.cpp -o main.o；g++ main.o -o main。",
        "pitfall": ".o 是小写字母 o，不是数字 0；目标文件还不能直接替代最终链接结果。",
    },
    "J232": {
        "answer": "编译器把高级语言源代码转换为目标代码；G++ 是 GNU 的 C++ 编译器",
        "explanation": "完整构建通常经历预处理、编译、汇编和链接。G++ 会按 C++ 规则处理 .cpp 文件并链接 C++ 标准库。",
        "example": "g++ -O2 code.cpp -o code：-O2 开启优化，-o code 指定输出文件名。",
        "pitfall": "-O2 中是大写字母 O，-o 中是小写字母 o；两者用途不同。",
    },
    "J235": {
        "promptText": "scanf/printf 与 cin/cout 的区别是什么？",
        "answer": "scanf/printf 是 C 风格格式化 I/O；cin/cout 是类型安全、可扩展的 C++ 流式 I/O",
        "explanation": "默认设置下 cin/cout 可能较慢，但关闭同步并解绑后通常足以应对竞赛输入：ios::sync_with_stdio(false); cin.tie(nullptr);。",
        "example": "long long x; scanf(\"%lld\",&x); 或 cin>>x;",
        "pitfall": "关闭同步后不要再混用 stdio 与 iostream；printf 输出 long long 要用 %lld。",
    },
    "J236": {
        "promptText": "如何把 double 类型变量保留小数点后两位输出？",
        "answer": "printf(\"%.2f\",x)，或 cout<<fixed<<setprecision(2)<<x",
        "explanation": "%.2f 和 fixed 配合 setprecision(2) 都表示小数点后显示两位，并按输出规则舍入；cout 方案需要 <iomanip>。",
        "example": "double x=2.5; cout<<fixed<<setprecision(2)<<x; // 2.50",
        "pitfall": "显示两位不代表二进制浮点值变得精确；比较浮点数仍要考虑误差。",
    },
    "J237": {
        "answer": "前面的逻辑值已能决定整体结果时，后面的表达式不再执行",
        "explanation": "A&&B 在 A 为假时跳过 B，A||B 在 A 为真时跳过 B。它常用于先检查合法性，再执行可能越界或解引用的操作。",
        "example": "i<n && a[i]>0：i>=n 时不会访问 a[i]。",
        "pitfall": "逻辑或写作 ||；右侧若含自增等副作用，短路时它不会发生。",
    },
    "J239": {
        "answer": "0 到 9",
        "explanation": "arr[10] 定义 10 个元素，C++ 下标从 0 开始，因此合法元素是 arr[0]～arr[9]。",
        "example": "for(int i=0;i<10;++i) arr[i]=0;",
        "pitfall": "arr[10] 已越界；把数组多开几个位置不能替代正确的边界判断。",
    },
    "J240": {
        "answer": "结束当前 switch，跳到 switch 之后继续执行",
        "explanation": "case 匹配后会从该位置向下执行；遇到 break 才离开 switch。若没有 break，就会继续执行后续 case，称为 case 穿透。",
        "example": "switch(k){ case 1: ++ans; break; default: --ans; break; }",
        "pitfall": "有意穿透时应写清注释；break 只跳出当前 switch 或循环，不会退出整个函数。",
    },
    "J241": {
        "answer": "使用 const 或 constexpr，例如 const int MAXN=100005;",
        "explanation": "const 提供类型检查并禁止通过该名字修改值；编译期常量优先使用 constexpr。#define 只是预处理文本替换。",
        "example": "constexpr double PI=3.141592653589793;",
        "pitfall": "常量应在定义时初始化；宏没有作用域和类型检查，能不用时尽量不用。",
    },
    "J242": {
        "answer": "使用 getline(cin, str)",
        "explanation": "cin>>str 遇到空格停止，getline 会读取一整行。若前面进行过格式化输入，应先清理残留换行。",
        "example": "string str; getline(cin,str);",
        "pitfall": "gets 不安全且已从现代 C/C++ 标准中移除，不应使用。",
    },
    "J244": {
        "answer": "基本操作次数与输入规模 n 同阶线性增长",
        "explanation": "O(n) 是渐近上界，忽略常数和低阶项。例如完整扫描一次长度为 n 的数组通常是 O(n)。它描述增长趋势，不直接等同于具体秒数。",
        "example": "for(int i=0;i<n;++i) sum+=a[i]; 执行 n 次加法。",
        "pitfall": "大 O 中是大写字母 O，不是数字 0；实际速度还受常数、硬件和操作类型影响。",
    },
    "J245": {
        "answer": "反复比较并交换相邻逆序元素，每轮把一个较大元素推到末尾；时间复杂度 O(n^2)",
        "explanation": "冒泡排序最多进行 n-1 轮，相邻逆序就交换。它是稳定排序，额外空间 O(1)，但大数据通常使用 O(n log n) 的 std::sort。",
        "example": "3,1,2 第一轮：交换 3 和 1 得 1,3,2，再交换 3 和 2 得 1,2,3。",
        "pitfall": "“一轮无交换则提前结束”只改善较有序数据，最坏复杂度仍是 O(n^2)。",
    },
    "J246": {
        "answer": "遍历所有候选情况，并逐一判断是否满足题目条件",
        "explanation": "枚举是最直接的搜索方法。关键是确定枚举对象、边界与判定条件，保证不重不漏；候选很多时需剪枝或换用更优算法。",
        "example": "枚举 1～n 的每个 i，检查它是否为 n 的约数。",
        "pitfall": "先按数据范围估算候选数量；边界写错会导致漏解、重复或越界。",
    },
    "J251": {
        "answer": "后进先出（LIFO）；例如括号匹配、表达式求值或函数调用栈",
        "explanation": "栈只在栈顶插入和删除，最后入栈的元素最先出栈。C++ 可使用 std::stack，也可用数组加 top 下标模拟。",
        "example": "括号匹配时，遇到左括号入栈，遇到右括号就与栈顶左括号配对。",
        "pitfall": "不是“先进后出”的标准简称，而是后进先出；top/pop 前必须判空。",
    },
    "J254": {
        "answer": "用二维数组记录任意两顶点之间是否有边或边权的图表示法",
        "explanation": "g[i][j] 表示 i 到 j 的连接关系。查询一条边是 O(1)，但空间为 O(V^2)，适合顶点较少或稠密图。",
        "example": "无向无权边 (2,3) 可令 g[2][3]=g[3][2]=1。",
        "pitfall": "大规模稀疏图使用邻接矩阵会耗费过多内存，应使用邻接表。",
    },
    "J256": {
        "answer": "string::npos",
        "explanation": "std::string::find 找不到目标时返回 string::npos，它是 size_type 的特殊最大值。判断时应与 string::npos 明确比较。",
        "example": "if(s.find(t)==string::npos) cout<<\"not found\";",
        "pitfall": "不能用 find 结果是否大于 0 判断：在位置 0 找到时返回 0，而找不到时返回 npos。",
    },
}


def ensure_teaching_details(question: dict) -> None:
    """Fill cards whose source back has no separate example or caution block."""
    answer = question["answer"]
    category = question["category"]
    if not question.get("example"):
        examples = {
            "C++语法": f"例如，可以围绕“{answer}”写一个最小 C++ 片段，并分别观察正常输入和边界输入下的结果。",
            "算法": f"例如，先在 3～5 个元素的小样例上手工模拟“{answer}”的执行过程，再对照每一步的状态变化。",
            "数据结构": f"例如，用少量元素执行一次插入、删除或查询，画出结构变化，即可验证“{answer}”在操作中的作用。",
            "计算机基础": f"例如，在实际系统或程序中找到“{answer}”对应的场景，并说明输入、处理过程和得到的结果。",
            "奥数与逻辑": f"例如，先代入一个规模很小的具体数值，按“{answer}”逐步计算，再推广到题目的一般情况。",
        }
        question["example"] = examples[category]
    if not question.get("pitfall"):
        pitfalls = {
            "C++语法": "注意运算符、括号、大小写和变量类型；代码能编译不代表边界情况一定正确。",
            "算法": "不要只记结论，还要说明适用条件、执行步骤和复杂度；数据范围变化时算法可能不再适用。",
            "数据结构": "不要只说结构名称；应结合具体操作说明元素如何存放、访问，以及边界或空结构如何处理。",
            "计算机基础": "不要只背术语；回答时要把它的具体作用、使用场景和相近概念的区别说清楚。",
            "奥数与逻辑": "注意题目条件、计数范围和是否包含边界情况；不要用小样例的现象代替一般性证明。",
        }
        question["pitfall"] = pitfalls[category]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ocr", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    pairs = find_cards(json.loads(args.ocr.read_text(encoding="utf-8")))
    if len(pairs) != 256:
        raise SystemExit(f"应有 256 对题卡，实际得到 {len(pairs)} 对")

    questions = []
    for number in range(1, 257):
        card_id = f"J{number:03d}"
        pair = pairs.get(card_id, {})
        if set(pair) != {"front", "back"}:
            raise SystemExit(f"{card_id} 正反面不完整")
        category, difficulty, prompt = front_fields(pair["front"])
        answer, explanation, example, pitfall = back_fields(pair["back"])
        question = {
            "id": card_id,
            "type": "FILL_BLANK",
            "category": category,
            "difficulty": difficulty,
            "promptText": prompt,
            "imageUrl": f"/quiz-cards/{card_id}.webp",
            "answer": answer,
            "explanation": explanation,
            "example": example,
            "pitfall": pitfall,
            "options": [],
            "correctOptionIds": [],
        }
        apply_overrides(question)
        question.update(CURATED_FIXES.get(card_id, {}))
        ensure_teaching_details(question)
        questions.append(question)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(questions, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"写入 {len(questions)} 道题: {args.output}")


if __name__ == "__main__":
    main()
