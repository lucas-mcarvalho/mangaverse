package com.mangaverse.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsService.SettingsView get() {
        return settings.view();
    }

    @PutMapping
    public SettingsService.SettingsView update(@RequestBody SettingsService.SettingsUpdate update) {
        return settings.update(update);
    }
}
