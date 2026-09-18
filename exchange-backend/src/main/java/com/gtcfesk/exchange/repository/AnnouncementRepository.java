package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    
    /**
     * 查询所有已发布的公告，按优先级和时间排序
     */
    @Query("SELECT a FROM Announcement a WHERE a.status = 'PUBLISHED' ORDER BY a.priority DESC, a.createdAt DESC")
    List<Announcement> findAllPublished();
    
    /**
     * 按语言查询所有已发布的公告，按优先级和时间排序
     */
    @Query("SELECT a FROM Announcement a WHERE a.status = 'PUBLISHED' AND a.language = :language ORDER BY a.priority DESC, a.createdAt DESC")
    List<Announcement> findAllPublishedByLanguage(@Param("language") String language);
    
    /**
     * 查询最新的已发布公告
     */
    @Query("SELECT a FROM Announcement a WHERE a.status = 'PUBLISHED' ORDER BY a.priority DESC, a.createdAt DESC")
    Optional<Announcement> findLatestPublished();
    
    /**
     * 按语言查询最新的已发布公告
     */
    @Query("SELECT a FROM Announcement a WHERE a.status = 'PUBLISHED' AND a.language = :language ORDER BY a.priority DESC, a.createdAt DESC")
    Optional<Announcement> findLatestPublishedByLanguage(@Param("language") String language);
    
    /**
     * 查询所有公告（包括草稿和隐藏的），按创建时间倒序
     */
    List<Announcement> findAllByOrderByCreatedAtDesc();
}



