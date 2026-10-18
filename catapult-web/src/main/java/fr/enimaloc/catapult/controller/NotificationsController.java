package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The bell's menu fragment (notifications.js swaps it into the top bar) and the read actions,
 * forwarded to catapult-api with its status. Members only: a visitor has no notifications (401).
 */
@Controller
@RequestMapping("/spa/notifications")
@RequiredArgsConstructor
public class NotificationsController {
    private final ApiService apiService;

    /** The newest notifications; the snapshot is null when catapult-api is unreachable (the menu then says so). */
    @GetMapping("/menu")
    String menu(Model model) {
        requireSession();
        model.addAttribute("snapshot", apiService.notificationSnapshot());
        return "fragments/notifications :: menu";
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@PathVariable UUID id) {
        requireSession();
        return ResponseEntity.status(apiService.markNotificationRead(id)).build();
    }

    @PostMapping("/read-all")
    ResponseEntity<Void> readAll() {
        requireSession();
        return ResponseEntity.status(apiService.markAllNotificationsRead()).build();
    }

    private static void requireSession() {
        if (!ApiClient.hasSession()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
