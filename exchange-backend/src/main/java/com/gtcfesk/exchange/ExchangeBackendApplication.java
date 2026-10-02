package com.gtcfesk.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(repositoryFactoryBeanClass = com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class)
public class ExchangeBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeBackendApplication.class, args);
    }
}





