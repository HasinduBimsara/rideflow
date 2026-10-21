package lk.boc.ride;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        return Map.of(
            "project", "RideFlow",
            "status", "Backend is running"
        );
    }
}