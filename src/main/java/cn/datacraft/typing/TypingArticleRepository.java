package cn.datacraft.typing;

import org.springframework.data.jpa.repository.JpaRepository;

interface TypingArticleRepository extends JpaRepository<TypingArticleEntity, String> {}

interface TypingArticleSeedStateRepository extends JpaRepository<TypingArticleSeedState, String> {}
