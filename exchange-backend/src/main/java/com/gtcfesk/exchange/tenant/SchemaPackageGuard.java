package com.gtcfesk.exchange.tenant;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;

/** Checked before a DataSource becomes available to ORM, startup writers or scheduled jobs. */
@Component
public final class SchemaPackageGuard implements BeanPostProcessor {
    @Override public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
        if (bean instanceof DataSource) {
            try (Connection connection = ((DataSource) bean).getConnection()) { verify(connection, packagedEpoch()); }
            catch (Exception failure) { throw new BeanInitializationException("数据库 schema 与当前发布包不兼容；保持停写", failure); }
        }
        return bean;
    }
    static long packagedEpoch() throws Exception {
        try (InputStream input = SchemaPackageGuard.class.getResourceAsStream("/META-INF/mt705-schema-epoch")) {
            if (input == null) throw new IllegalStateException("发布包缺少 schema epoch");
            byte[] buffer = new byte[32]; int count = input.read(buffer);
            if (count < 1 || input.read() != -1) throw new IllegalStateException("发布包 schema epoch 格式无效");
            String epoch = new String(buffer, 0, count, StandardCharsets.US_ASCII).trim();
            if (!epoch.matches("20[0-9]{8}")) throw new IllegalStateException("发布包 schema epoch 格式无效");
            return Long.parseLong(epoch);
        }
    }
    static void verify(Connection connection, long epoch) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName();
        // Existing unit tests use an in-memory H2 database; never exempt a physical MySQL target.
        if ("H2".equals(product) && connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) return;
        if (!"MySQL".equals(product)) throw new IllegalStateException("不支持的物理数据库");
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT MAX(minimum_application_epoch) FROM tenant_schema_version")) {
            if (!row.next()) throw new IllegalStateException("schema 版本收据缺失");
            long required = row.getLong(1);
            if (row.wasNull() || required <= 0 || epoch < required) throw new IllegalStateException("旧包或未迁移 schema 拒绝启动");
        }
        // Activation approval and tenant state remain separate fail-closed business gates.
    }
}
