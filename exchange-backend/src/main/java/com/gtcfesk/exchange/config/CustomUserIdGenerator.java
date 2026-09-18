package com.gtcfesk.exchange.config;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 自定义用户ID生成器
 * 用户ID从7000001开始
 */
public class CustomUserIdGenerator implements IdentifierGenerator {

    private static final long ID_OFFSET = 7000000L;

    @Override
    public Serializable generate(SharedSessionContractImplementor session, Object object) throws HibernateException {
        Connection connection = session.connection();
        try {
            Statement statement = connection.createStatement();
            
            // 查询当前最大ID
            ResultSet rs = statement.executeQuery("SELECT MAX(id) as maxId FROM user_account");
            
            if (rs.next()) {
                Long maxId = rs.getLong("maxId");
                if (maxId == null || maxId == 0) {
                    // 如果表为空，返回第一个ID: 7000001
                    return ID_OFFSET + 1;
                } else {
                    // 返回最大ID + 1
                    return maxId + 1;
                }
            }
            
            return ID_OFFSET + 1;
            
        } catch (SQLException e) {
            throw new HibernateException("无法生成用户ID", e);
        }
    }
}

