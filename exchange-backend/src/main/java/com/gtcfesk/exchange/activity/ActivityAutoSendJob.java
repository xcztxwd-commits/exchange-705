package com.gtcfesk.exchange.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ActivityAutoSendJob {
 private final ActivityService service;
 @Scheduled(fixedDelay=60000,initialDelay=15000)
 public void sendDue(){service.autoSendDue();}
}
