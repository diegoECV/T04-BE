package vallegrande.edu.pe.visons.rest;

import java.time.LocalDateTime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestRest {

    @GetMapping("/health")
    public String health() {
        return "{\"status\": \"Backend is running\", \"timestamp\": \"" + LocalDateTime.now() + "\"}";
    }

    @PostMapping("/echo")
    public Object echo(@RequestBody Object body) {
        return body;
    }
}
