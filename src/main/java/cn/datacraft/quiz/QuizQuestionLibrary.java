package cn.datacraft.quiz;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class QuizQuestionLibrary {
    private final List<Question> questions;
    private final Map<String, Question> byId;

    public QuizQuestionLibrary(ObjectMapper mapper) {
        try (InputStream input = new ClassPathResource("quiz/questions-j.json").getInputStream()) {
            List<Question> loaded = mapper.readValue(input, new TypeReference<List<Question>>() {});
            validate(loaded);
            this.questions = Collections.unmodifiableList(new ArrayList<>(loaded));
            this.byId = Collections.unmodifiableMap(loaded.stream().collect(Collectors.toMap(
                    question -> question.id, question -> question, (left, right) -> left, LinkedHashMap::new)));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取问题抢答题库", exception);
        }
    }

    public List<Question> all() {
        return questions;
    }

    public Question require(String id) {
        Question question = byId.get(id);
        if (question == null) throw new NoSuchElementException("题目不存在");
        return question;
    }

    public List<Question> select(Collection<String> rawCategories, Collection<String> rawDifficulties) {
        Set<String> categories = normalizedSet(rawCategories);
        Set<String> difficulties = normalizedSet(rawDifficulties);
        return questions.stream()
                .filter(question -> categories.isEmpty() || categories.contains(question.category))
                .filter(question -> difficulties.isEmpty() || difficulties.contains(question.difficulty))
                .collect(Collectors.toList());
    }

    public List<String> categories() {
        return questions.stream().map(question -> question.category).distinct().sorted().collect(Collectors.toList());
    }

    public List<String> difficulties() {
        return questions.stream().map(question -> question.difficulty).distinct().sorted().collect(Collectors.toList());
    }

    private static Set<String> normalizedSet(Collection<String> values) {
        if (values == null) return Collections.emptySet();
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void validate(List<Question> loaded) {
        if (loaded == null || loaded.size() != 256) {
            throw new IllegalStateException("J 组题库必须恰好包含 256 道题");
        }
        Set<String> ids = new HashSet<>();
        for (int number = 1; number <= 256; number++) {
            String expected = String.format(Locale.ROOT, "J%03d", number);
            Question question = loaded.stream().filter(item -> expected.equals(item.id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("题库缺少 " + expected));
            if (!ids.add(question.id)) throw new IllegalStateException("题目编号重复: " + question.id);
            requireText(question.type, question.id, "题型");
            requireText(question.category, question.id, "分类");
            requireText(question.difficulty, question.id, "难度");
            requireText(question.promptText, question.id, "题面");
            requireText(question.imageUrl, question.id, "题面图片");
            requireText(question.answer, question.id, "答案");
            requireText(question.explanation, question.id, "解析");
            requireText(question.example, question.id, "示例");
            requireText(question.pitfall, question.id, "易错点");
            if (question.options == null) question.options = Collections.emptyList();
            if (question.correctOptionIds == null) question.correctOptionIds = Collections.emptyList();
        }
    }

    private static void requireText(String value, String id, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(id + " 缺少" + field);
        }
    }

    public static final class Question {
        public String id;
        public String type;
        public String category;
        public String difficulty;
        public String promptText;
        public String imageUrl;
        public String answer;
        public String explanation;
        public String example;
        public String pitfall;
        public List<Option> options = Collections.emptyList();
        public List<String> correctOptionIds = Collections.emptyList();
    }

    public static final class Option {
        public String id;
        public String text;
    }
}
