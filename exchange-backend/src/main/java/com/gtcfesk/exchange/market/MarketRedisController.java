package com.gtcfesk.exchange.market;
// The legacy Redis HTTP routes share MarketPriceController and MarketKlineController.
// This prevents stale Redis data from bypassing snapshot freshness and source backoff.
