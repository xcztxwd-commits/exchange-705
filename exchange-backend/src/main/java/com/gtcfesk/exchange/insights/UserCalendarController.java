package com.gtcfesk.exchange.insights;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/user/calendar/reminders") @RequiredArgsConstructor
public class UserCalendarController {
    private final CalendarService service;
    @GetMapping public List<Map<String,Object>> list(){return service.reminders(null);}
    @GetMapping("/{id}") public List<Map<String,Object>> get(@PathVariable String id){return service.reminders(id);}
    @PutMapping("/{id}") public Map<String,Object> set(@PathVariable String id,@RequestBody CalendarService.ReminderInput input){return service.setReminder(id,input);}
    @DeleteMapping("/{id}") public void cancel(@PathVariable String id,@RequestParam(defaultValue="15") int leadMinutes){service.cancelReminder(id,leadMinutes);}
}
