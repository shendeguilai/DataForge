package cn.datacraft.atcoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AtcoderStandingsParser {
    private final ObjectMapper mapper;

    public AtcoderStandingsParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AtcoderStandings.Snapshot parse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode taskInfo = requiredArray(root, "TaskInfo");
            JsonNode standingsData = requiredArray(root, "StandingsData");

            List<AtcoderStandings.Task> tasks = new ArrayList<>();
            for (JsonNode item : taskInfo) {
                String id = text(item, "TaskScreenName", "TaskName");
                if (id.isBlank()) continue;
                String label = text(item, "Assignment");
                String name = text(item, "TaskName", "TaskScreenName");
                tasks.add(new AtcoderStandings.Task(id, label, name, score(item, "Score")));
            }
            if (tasks.isEmpty()) throw new IllegalStateException("AtCoder 榜单没有返回题目列表");

            Map<String, AtcoderStandings.Entry> entries = new LinkedHashMap<>();
            for (JsonNode row : standingsData) {
                String username = text(row, "UserScreenName", "UserName");
                if (username.isBlank()) continue;
                JsonNode total = object(row, "TotalResult");
                JsonNode taskResultsNode = object(row, "TaskResults");
                Map<String, AtcoderStandings.TaskResult> taskResults = new LinkedHashMap<>();
                for (AtcoderStandings.Task task : tasks) {
                    JsonNode result = taskResultsNode.path(task.id());
                    if (!result.isObject()) continue;
                    taskResults.put(task.id(), new AtcoderStandings.TaskResult(
                            score(result, "Score"), number(result, "Elapsed"), integer(result, "Penalty"),
                            integer(result, "Failure"), integer(result, "Count"), bool(result, "Pending"),
                            bool(result, "Frozen"), text(result, "Status")
                    ));
                }
                AtcoderStandings.Entry entry = new AtcoderStandings.Entry(
                        username,
                        nullableInteger(row, "Rank"),
                        score(total, "Score"),
                        number(total, "Elapsed"),
                        integer(total, "Penalty"),
                        Map.copyOf(taskResults)
                );
                entries.put(username.toLowerCase(Locale.ROOT), entry);
            }
            return new AtcoderStandings.Snapshot(List.copyOf(tasks), Map.copyOf(entries));
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("AtCoder 榜单数据格式无法解析", ex);
        }
    }

    private static JsonNode requiredArray(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isArray()) throw new IllegalStateException("AtCoder 榜单缺少 " + name);
        return value;
    }

    private static JsonNode object(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isObject() ? value : MissingNode.getInstance();
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) return value.asText("").trim();
        }
        return "";
    }

    private static BigDecimal decimal(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try { return new BigDecimal(value.asText()); } catch (NumberFormatException ignored) {}
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal score(JsonNode node, String name) {
        return decimal(node, name).movePointLeft(2).stripTrailingZeros();
    }

    private static long number(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.canConvertToLong() ? value.asLong() : 0L;
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.canConvertToInt() ? value.asInt() : 0;
    }

    private static Integer nullableInteger(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.canConvertToInt() ? value.asInt() : null;
    }

    private static boolean bool(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isBoolean() && value.asBoolean();
    }
}
