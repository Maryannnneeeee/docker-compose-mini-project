package com.fx.api.web;

import com.fx.feed.AcceptingState;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The one lever fx-monitor's toggle pulls: whether the feed is currently writing ticks. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AcceptingState accepting;

    public AdminController(AcceptingState accepting) {
        this.accepting = accepting;
    }

    @GetMapping("/accepting")
    public Map<String, Boolean> get() {
        return Map.of("accepting", accepting.isOn());
    }

    @PostMapping("/accepting")
    public Map<String, Boolean> set(@RequestBody Map<String, Boolean> body) {
        boolean next = Boolean.TRUE.equals(body.get("accepting"));
        return Map.of("accepting", accepting.set(next));
    }
}
