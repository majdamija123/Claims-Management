package ma.cdg.claims.web;

import java.util.List;
import java.util.Map;
import ma.cdg.claims.security.CurrentUser;
import ma.cdg.claims.service.NotificationService;
import ma.cdg.claims.web.dto.NotificationDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** In-app notifications of the signed-in user. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final DtoMapper mapper;

    public NotificationController(NotificationService notifications, CurrentUser currentUser,
                                  DtoMapper mapper) {
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @GetMapping
    public List<NotificationDto> list(@RequestParam(defaultValue = "30") int limit) {
        return notifications.recentFor(currentUser.username(), limit).stream()
                .map(mapper::toDto)
                .toList();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.unreadCount(currentUser.username()));
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        notifications.markRead(id, currentUser.username());
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notifications.markAllRead(currentUser.username()));
    }
}
