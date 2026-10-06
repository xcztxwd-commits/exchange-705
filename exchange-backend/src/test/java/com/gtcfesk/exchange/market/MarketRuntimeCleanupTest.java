package com.gtcfesk.exchange.market;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketRuntimeCleanupTest {
    @Test void failedCleanupEvictsActualHikariSessionInsteadOfRecyclingIt() throws Exception {
        try(HikariDataSource pool=new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:cleanup_"+java.util.UUID.randomUUID());
            pool.setUsername("sa");pool.setMaximumPoolSize(1);pool.setMinimumIdle(0);
            Connection unsafe=pool.getConnection();
            Connection physical=unsafe.unwrap(org.h2.jdbc.JdbcConnection.class);
            MarketRuntime.discardConnection(pool,unsafe);
            try(Connection next=pool.getConnection()) {
                assertNotSame(physical,next.unwrap(org.h2.jdbc.JdbcConnection.class),"A session retaining old fences must not re-enter the pool");
            }
        }
    }
    @Test void nonHikariSessionIsAbortedBeforeClose() throws Exception {
        Connection unsafe=mock(Connection.class);
        MarketRuntime.discardConnection(mock(DataSource.class),unsafe);
        org.mockito.InOrder order=inOrder(unsafe);
        order.verify(unsafe).abort(any(java.util.concurrent.Executor.class));order.verify(unsafe).close();
    }
}
