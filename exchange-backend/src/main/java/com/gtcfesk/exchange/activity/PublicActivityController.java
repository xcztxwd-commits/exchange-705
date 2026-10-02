package com.gtcfesk.exchange.activity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class PublicActivityController {
 private final ActivityService activities;
 @GetMapping("/api/user/announcements/activities")
 public Object list(@RequestParam(defaultValue="ANONYMOUS_HOME") String position,@RequestParam(defaultValue="0") int page){return activities.publicActivities(position,page);}
}
