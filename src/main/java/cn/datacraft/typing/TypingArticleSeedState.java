package cn.datacraft.typing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "typing_article_seed_state")
class TypingArticleSeedState {
    @Id
    @Column(name = "id", length = 40)
    private String id;

    @Column(name = "initialized_at", nullable = false)
    private LocalDateTime initializedAt;

    protected TypingArticleSeedState() {}

    TypingArticleSeedState(String id) {
        this.id = id;
        this.initializedAt = LocalDateTime.now();
    }
}
