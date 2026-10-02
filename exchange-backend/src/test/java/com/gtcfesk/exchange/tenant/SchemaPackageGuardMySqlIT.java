package com.gtcfesk.exchange.tenant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;

/** Actual, independently restored native MySQL schema; no mutation of its version receipt. */
class SchemaPackageGuardMySqlIT {
    @Test void actualMysqlRejectsOldPackageAndAcceptsPackagedCurrentEpoch() throws Exception {
        DriverManagerDataSource source=DedicatedMysqlFixture.fromProperty("manual.mysql.fixture");
        try(Connection c=source.getConnection()) {
            assertEquals("MySQL",c.getMetaData().getDatabaseProductName());
            assertThrows(IllegalStateException.class,()->SchemaPackageGuard.verify(c,0));
            assertThrows(IllegalStateException.class,()->SchemaPackageGuard.verify(c,2026093006L));
            SchemaPackageGuard.verify(c,SchemaPackageGuard.packagedEpoch());
        }
        assertSame(source,new SchemaPackageGuard().postProcessAfterInitialization(source,"nativeFixture"));
    }
}
