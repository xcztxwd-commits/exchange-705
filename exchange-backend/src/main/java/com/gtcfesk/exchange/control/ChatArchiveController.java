package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
/** Independent CONTROL only; tokens never appear in download URLs. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/control/tenants/{tenant}/support")
public class ChatArchiveController {
 private final ChatArchiveService archives;
 @PostMapping("/conversations/{conversation}/archive-jobs") public Object start(@PathVariable long tenant,@PathVariable long conversation,@RequestBody Input input){return ControlController.ok(archives.start(tenant,conversation,input.requestKey,input.reason));}
 @GetMapping("/conversations/{conversation}/archive-jobs") public Object list(@PathVariable long tenant,@PathVariable long conversation){return ControlController.ok(archives.list(tenant,conversation));}
 @GetMapping("/archive-jobs/{id}") public Object status(@PathVariable long tenant,@PathVariable String id){return ControlController.ok(archives.status(tenant,id));}
 @PostMapping("/archive-jobs/{id}/retry") public Object retry(@PathVariable long tenant,@PathVariable String id,@RequestBody Input input){return ControlController.ok(archives.retry(tenant,id,input.reason));}
 @GetMapping("/archive-jobs/{id}/files/{name}") public ResponseEntity<FileSystemResource> file(@PathVariable long tenant,@PathVariable String id,@PathVariable String name){java.nio.file.Path path=archives.download(tenant,id,name);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename="+name).contentType(name.endsWith(".png")?MediaType.IMAGE_PNG:MediaType.APPLICATION_JSON).body(new FileSystemResource(path));}
 public static class Input{public String requestKey,reason;}
}
