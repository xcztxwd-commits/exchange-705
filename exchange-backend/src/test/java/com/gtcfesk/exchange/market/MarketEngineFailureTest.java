package com.gtcfesk.exchange.market;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketEngineFailureTest {
    @Test void jdbcCauseAndNextExceptionNormalizeWithoutTreatingEvery45000AsFence(){
        SQLException wrapper=new SQLException("outer driver message","HY000",0);
        wrapper.setNextException(new SQLException("ENGINE_FENCED","45000",1644));
        assertEquals("ENGINE_FENCED",MarketEngineFailure.normalize(new IllegalStateException("wrapped",wrapper)));
        assertEquals("COMMAND_FAILED",MarketEngineFailure.normalize(new SQLException("business trigger rejected","45000",1644)));
        assertEquals("ENGINE_BUSY",MarketEngineFailure.normalize(new IllegalStateException("wrapped",new IllegalStateException("ENGINE_BUSY: held"))));
        assertEquals("ENGINE_BUDGET",MarketEngineFailure.normalize(new IllegalStateException("ENGINE_BUDGET: expired")));
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(new SQLException("deadlock","40001",1213)));
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(new SQLException("connection lost","08S01",0)));
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(new SQLException("commit acknowledgement lost","08007",0)));
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(new SQLException("query interrupted","70100",1317)));
        assertEquals("COMMAND_FAILED",MarketEngineFailure.normalize(new SQLException("invalid SQL","42000",1064)));
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(new SQLException("lock wait expired","HY000",1205)));
        assertEquals("ENGINE_BUDGET",MarketEngineFailure.normalize(new SQLException("query execution interrupted","HY000",3024)));
        assertEquals("ENGINE_BUDGET",MarketEngineFailure.normalize(new java.sql.SQLTimeoutException("statement timeout")));
        assertEquals("ENGINE_BUDGET",MarketEngineFailure.normalize(new IllegalStateException("wrapped",new org.springframework.transaction.TransactionTimedOutException("Transaction deadline exceeded"))));
    }
}
