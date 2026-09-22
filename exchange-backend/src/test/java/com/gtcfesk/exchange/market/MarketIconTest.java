package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

class MarketIconTest {
    @Test void bundledLogosFlagsAndFallbacksAreValidStandaloneSvg() throws Exception {
        MarketIconController icons=new MarketIconController();
        for(String[] key:new String[][]{{"crypto","BTC"},{"crypto","ETH"},{"crypto","UNKNOWNCOIN"},{"forex","USD-JPY"},{"forex","EUR-GBP"},{"forex","XXX-YYY"},{"stocks","AAPL"},{"stocks","UNKNOWN"},{"metal","XAU"},{"oil","CLF"},{"index","NDX"}}) {
            byte[] data=icons.icon(key[0],key[1]).getBody();assertNotNull(data);
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(data));
            String text=new String(data,StandardCharsets.UTF_8);assertFalse(text.contains("<script"));
            if("USD-JPY".equals(key[1])){assertTrue(text.contains("flag-icons-us"));assertTrue(text.contains("flag-icons-jp"));}
            if("BTC".equals(key[1]))assertTrue(text.contains("#F7931A"));
        }
        assertEquals(404,icons.icon("crypto","../etc/passwd").getStatusCodeValue());
        assertEquals(404,icons.icon("crypto","<script>").getStatusCodeValue());
        TradingSymbol symbol=new TradingSymbol();symbol.setSymbol("JPY=X");symbol.setSourceCategory("Forex");symbol.setBaseCurrency("USD");symbol.setQuoteCurrency("JPY");
        assertEquals("/market/icons/forex/USD-JPY.svg?v=1",MarketIconController.url(symbol));
        symbol.setCategory("Crypto");assertEquals("/market/icons/forex/USD-JPY.svg?v=1",MarketIconController.url(symbol));
        symbol.setSourceCategory("CryptoPerpetual");symbol.setBaseCurrency("ETH");assertEquals("/market/icons/crypto/ETH.svg?v=1",MarketIconController.url(symbol));
    }
}
