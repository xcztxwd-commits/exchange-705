package com.gtcfesk.exchange.tenant;

import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchemaPackageGuardTest {
    @Test void packagedEpochIsFixedResourceNotEnvironmentOverride() throws Exception {
        assertEquals(2026100404L, SchemaPackageGuard.packagedEpoch());
    }
    @Test void olderPackageAndMissingVersionFailBeforeDatasourcePublication() throws Exception {
        Connection c = mock(Connection.class); DatabaseMetaData m = mock(DatabaseMetaData.class);
        Statement s = mock(Statement.class); ResultSet r = mock(ResultSet.class);
        when(c.getMetaData()).thenReturn(m); when(m.getDatabaseProductName()).thenReturn("MySQL");
        when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r);
        when(r.next()).thenReturn(true); when(r.getLong(1)).thenReturn(2026100101L);
        assertThrows(IllegalStateException.class, () -> SchemaPackageGuard.verify(c, 2026093006L));
        SchemaPackageGuard.verify(c, 2026100101L);
        when(r.wasNull()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> SchemaPackageGuard.verify(c, 2026100101L));
        when(s.executeQuery(anyString())).thenThrow(new SQLException("missing version table"));
        assertThrows(SQLException.class, () -> SchemaPackageGuard.verify(c, 2026100101L));
    }
    @Test void onlyInMemoryH2MayBeATestDatasource() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:guard_unit", "sa", "")) {
            SchemaPackageGuard.verify(c, 2026100101L);
        }
        Connection c=mock(Connection.class);DatabaseMetaData m=mock(DatabaseMetaData.class);
        when(c.getMetaData()).thenReturn(m);when(m.getDatabaseProductName()).thenReturn("H2");when(m.getURL()).thenReturn("jdbc:h2:file:/physical");
        assertThrows(IllegalStateException.class, () -> SchemaPackageGuard.verify(c, 2026100101L));
    }
}
