package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnnouncementRepository extends com.gtcfesk.exchange.tenant.TenantRepository<Announcement, Long> {
    
    /**
     * 查询所有已发布的公告，按优先级和时间排序
     */
    @org.springframework.data.jpa.repository.Query("SELECT a FROM Announcement a WHERE a.tenantId = ?#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (a.status = 'PUBLISHED') ORDER BY a.priority DESC, COALESCE(a.displayAt, a.createdAt) DESC, a.id DESC")
    List<Announcement> findAllPublished();
    
    /**
     * 按语言查询所有已发布的公告，按优先级和时间排序
     */
    @org.springframework.data.jpa.repository.Query("SELECT a FROM Announcement a WHERE a.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (a.status = 'PUBLISHED' AND a.language = :language) ORDER BY a.priority DESC, COALESCE(a.displayAt, a.createdAt) DESC, a.id DESC")
    List<Announcement> findAllPublishedByLanguage(@Param("language") String language);
    
    /**
     * 查询最新的已发布公告
     */
    Optional<Announcement> findFirstByTenantIdAndStatusOrderByPriorityDescCreatedAtDescIdDesc(Long tenantId, String status);
    default Optional<Announcement> findLatestPublished() {
        return latestPublished(null, org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();
    }
    
    /**
     * 按语言查询最新的已发布公告
     */
    Optional<Announcement> findFirstByTenantIdAndStatusAndLanguageOrderByPriorityDescCreatedAtDescIdDesc(Long tenantId, String status, String language);
    default Optional<Announcement> findLatestPublishedByLanguage(String language) {
        return latestPublished(language, org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();
    }
    
    /**
     * 查询所有公告（包括草稿和隐藏的），按创建时间倒序
     */
    List<Announcement> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    @Query("SELECT a FROM Announcement a WHERE a.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND a.status = 'PUBLISHED' AND (:language IS NULL OR a.language = :language) ORDER BY a.priority DESC, COALESCE(a.displayAt, a.createdAt) DESC, a.id DESC")
    List<Announcement> latestPublished(@Param("language") String language, org.springframework.data.domain.Pageable pageable);
}



