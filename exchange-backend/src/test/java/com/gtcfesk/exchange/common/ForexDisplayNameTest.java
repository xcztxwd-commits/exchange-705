package com.gtcfesk.exchange.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.entity.TradingSymbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForexDisplayNameTest {
    @Test void namesAreSeparateFromProviderCodesInAllApiObjects() {
        TradingSymbol symbol = new TradingSymbol();
        symbol.setSymbol("JPY=X"); symbol.setCategory("Forex");
        symbol.setBaseCurrency("JPY"); symbol.setQuoteCurrency("JPY");
        symbol.setName("JPY=X");
        ContractOrder contract = new ContractOrder(); contract.setSymbol("JPY=X");
        OptionOrder option = new OptionOrder(); option.setSymbol("EURUSD=X");
        ObjectMapper json = new ObjectMapper();

        assertEquals("JPY=X", json.valueToTree(symbol).path("symbol").asText());
        assertEquals("USD/JPY", json.valueToTree(symbol).path("displayName").asText());
        assertEquals("USD/JPY", json.valueToTree(contract).path("displayName").asText());
        assertEquals("EUR/USD", json.valueToTree(option).path("displayName").asText());
        assertEquals("USD/JPY", ForexDisplayName.of("USDJPY"));
        assertEquals("BTCUSD", ForexDisplayName.of("BTCUSD"));
        assertEquals("CL=F", ForexDisplayName.of("CL=F"));
    }
}
