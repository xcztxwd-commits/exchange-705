package com.gtcfesk.exchange.market;

import java.sql.SQLException;
import java.util.*;

/** Normalize wrapped JDBC trigger failures without mistaking every SQLSTATE 45000 for fencing. */
public final class MarketEngineFailure {
    private MarketEngineFailure() {}
    public static String normalize(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending=new ArrayDeque<>();if(failure!=null)pending.add(failure);
        String reason="COMMAND_FAILED";
        while(!pending.isEmpty()){
            Throwable current=pending.removeFirst();if(!seen.add(current))continue;
            String message=current.getMessage()==null?"":current.getMessage();
            if(message.contains("ENGINE_FENCED"))return "ENGINE_FENCED";
            if(message.startsWith("ENGINE_BUDGET") || current instanceof org.springframework.transaction.TransactionTimedOutException)reason="ENGINE_BUDGET";
            else if(message.startsWith("ENGINE_BUSY") && !"ENGINE_BUDGET".equals(reason))reason="ENGINE_BUSY";
            else if(current instanceof org.springframework.dao.TransientDataAccessException && "COMMAND_FAILED".equals(reason))reason="ENGINE_TRANSIENT";
            if(current instanceof SQLException){
                SQLException sql=(SQLException)current;
                if(sql instanceof java.sql.SQLTimeoutException || sql.getErrorCode()==3024)reason="ENGINE_BUDGET";
                else if((sql.getErrorCode()==1205 || sql.getErrorCode()==1317 || sql.getSQLState()!=null && (sql.getSQLState().startsWith("40") || sql.getSQLState().startsWith("08") || sql.getSQLState().equals("70100"))) && "COMMAND_FAILED".equals(reason))reason="ENGINE_TRANSIENT";
                if(sql.getNextException()!=null)pending.add(sql.getNextException());
            }
            if(current.getCause()!=null)pending.add(current.getCause());
        }
        return reason;
    }
}
