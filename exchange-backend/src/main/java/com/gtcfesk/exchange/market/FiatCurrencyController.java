package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.user.FiatCurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/market/currencies")
public class FiatCurrencyController {
    private final ForexQuoteMarketService market;

    @GetMapping
    public Map<String, Object> rates() {
        Map<String, Object> rates = new LinkedHashMap<>();
        for (String currency : FiatCurrencyService.CURRENCIES) {
            rates.put(currency, market.conversion(currency, "yahoo"));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("baseCurrency", "USD");
        result.put("rates", rates);
        return result;
    }
}
