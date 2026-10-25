package com.smartgrow;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")   // lets the HTML page (opened from a file or another port) call this API
public class AnalyzeController {

    private final RiskEngine engine;

    public AnalyzeController(RiskEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/analyze")
    public RiskEngine.Response analyze(@RequestBody RiskEngine.Request request) {
        return engine.analyze(request);
    }
}