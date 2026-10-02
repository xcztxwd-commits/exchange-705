package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminAnnouncementController;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean;
import com.gtcfesk.exchange.user.AnnouncementController;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.*;
import static org.mockito.Mockito.*;

/** Actual SQL/JPA storage; only licensed feature/config lookup is stubbed, never inbox data. */
@TestConfiguration @EnableTransactionManagement(proxyTargetClass=true)
@EnableJpaRepositories(repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class,basePackageClasses=AnnouncementRepository.class)
@Import({UnifiedInboxService.class,UnifiedInboxController.class,AdminAnnouncementController.class,AnnouncementController.class})
public class UnifiedInboxFixture {
    @Bean DataSource dataSource() {
        String port=System.getProperty("t04.mysqlPort");
        if(port==null)return new DriverManagerDataSource("jdbc:h2:mem:t04_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","sa","");
        if(!"33404".equals(port) || !"true".equals(System.getenv("T04_DISPOSABLE_MYSQL")))throw new IllegalArgumentException("T04 disposable database only");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/t04_inbox?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC","root",System.getenv("T04_MYSQL_PASSWORD"));
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
        LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean(); f.setDataSource(ds);
        f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.admin","com.gtcfesk.exchange.support","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.control");
        f.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); Properties p=new Properties();
        p.setProperty("hibernate.hbm2ddl.auto","create-drop");
        p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        p.setProperty("hibernate.jdbc.time_zone","UTC"); f.setJpaProperties(p); return f;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
    @Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
    @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
    @Bean com.gtcfesk.exchange.control.TenantReadinessService readiness(){return mock(com.gtcfesk.exchange.control.TenantReadinessService.class);}
    @Bean SupportSettings.Settings supportConfig(){SupportSettings.Settings s=new SupportSettings.Settings();s.mode="internal";s.inboxEnabled=true;return s;}
    @Bean SupportSettings settings(SupportSettings.Settings config){SupportSettings s=mock(SupportSettings.class);when(s.get()).thenReturn(config);return s;}
    @Bean TenantPolicyService policy(){TenantPolicyService p=mock(TenantPolicyService.class);when(p.featureEnabled("activity")).thenReturn(true);return p;}
}
