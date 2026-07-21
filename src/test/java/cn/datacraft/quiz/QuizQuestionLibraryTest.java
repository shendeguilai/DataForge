package cn.datacraft.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class QuizQuestionLibraryTest {
    @Test
    void bundledJGroupContainsEveryQuestionAndDetailedAnswers() {
        QuizQuestionLibrary library = new QuizQuestionLibrary(new ObjectMapper());
        assertEquals(256, library.all().size());
        assertEquals(256, new HashSet<>(library.all().stream().map(question -> question.id)
                .collect(Collectors.toList())).size());
        library.all().forEach(question -> {
            assertFalse(question.promptText.isBlank(), question.id);
            assertFalse(question.answer.isBlank(), question.id);
            assertTrue(question.explanation.length() >= 18, question.id);
            assertNotNull(question.example, question.id);
            assertFalse(question.example.isBlank(), question.id);
            assertNotNull(question.pitfall, question.id);
            assertFalse(question.pitfall.isBlank(), question.id);
            assertFalse(question.answer.contains("ID:"), question.id);
            assertFalse((question.promptText + question.answer + question.explanation).contains("�"), question.id);
            assertEquals("/quiz-cards/" + question.id + ".webp", question.imageUrl);
            assertNotNull(getClass().getResource("/static" + question.imageUrl), question.imageUrl);
        });
        QuizQuestionLibrary.Question linux = library.require("J006");
        assertEquals("ls", linux.answer);
        assertTrue(linux.explanation.contains("列出目录内容"));
        assertTrue(linux.example.contains("ls -a"));
        assertTrue(linux.pitfall.contains("小写字母"));
        QuizQuestionLibrary.Question storage = library.require("J224");
        assertTrue(storage.answer.contains("1 Byte = 8 bits"));
        assertTrue(storage.example.contains("3355 万"));
        assertFalse(library.require("J161").answer.startsWith("ID:"));
    }
}
