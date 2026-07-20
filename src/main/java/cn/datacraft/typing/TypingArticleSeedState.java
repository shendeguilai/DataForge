package cn.datacraft.typing;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "typing_article_seed_state")
class TypingArticleSeedState {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false)
    private LocalDateTime initializedAt;

    protected TypingArticleSeedState() {}

    TypingArticleSeedState(String id) {
        this.id = id;
        this.initializedAt = LocalDateTime.now();
    }
}
