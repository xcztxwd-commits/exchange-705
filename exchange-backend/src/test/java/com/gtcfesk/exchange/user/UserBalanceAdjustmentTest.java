package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.BalanceAdjustment;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UserBalanceAdjustmentTest {
    @Test void projectsEachNonzeroAccountAndKeepsInternalDataPrivate() {
        BalanceAdjustment record=new BalanceAdjustment();record.setId(8L);record.setActorId(99L);record.setReason("private operator note");
        record.setChanges("FUND:100->150;delta=50;CONTRACT:20->10;delta=-10;OPTION:0->0;delta=0");
        List<Map<String,Object>> rows=UserBalanceAdjustmentController.publicChanges(record);
        assertEquals(2,rows.size());assertEquals("ADMIN_IN",rows.get(0).get("type"));assertEquals("ADMIN_OUT",rows.get(1).get("type"));
        assertEquals(new BigDecimal("-10"),rows.get(1).get("amount"));
        assertFalse(rows.toString().contains("private operator note"));assertFalse(rows.get(0).containsKey("actorId"));
        record.setChanges("PROFILE_SAVE");assertTrue(UserBalanceAdjustmentController.publicChanges(record).isEmpty());
        record.setChanges("CANCELLED");assertTrue(UserBalanceAdjustmentController.publicChanges(record).isEmpty());
    }
}
