package cn.datacraft.typing;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

@Entity
@Table(name = "typing_articles")
class TypingArticleEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 12)
    private String category;

    @Lob
    @Column(nullable = false)
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
