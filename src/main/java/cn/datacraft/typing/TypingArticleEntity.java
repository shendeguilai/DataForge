package cn.datacraft.typing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "typing_articles")
class TypingArticleEntity {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "title", nullable = false, length = 80)
    private String title;

    @Column(name = "category", nullable = false, length = 12)
    private String category;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    protected TypingArticleEntity() {}

    TypingArticleEntity(String id, String title, String category, String content) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.content = content;
    }

    String getId() { return id; }
    String getTitle() { return title; }
    String getCategory() { return category; }
    String getContent() { return content; }

    void update(String title, String category, String content) {
        this.title = title;
        this.category = category;
        this.content = content;
    }
}
