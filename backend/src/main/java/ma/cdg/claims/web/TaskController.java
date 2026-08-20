package ma.cdg.claims.web;

import jakarta.validation.Valid;
import java.util.List;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.security.CurrentUser;
import ma.cdg.claims.service.CompleteTaskCommand;
import ma.cdg.claims.service.TaskService;
import ma.cdg.claims.web.dto.ClaimDtos;
import ma.cdg.claims.web.dto.PageResponse;
import ma.cdg.claims.web.dto.TaskDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The task inbox. Every response is built from the engine's own view of the open user
 * tasks, joined with the complaint each one belongs to.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService tasks;
    private final CurrentUser currentUser;
    private final DtoMapper mapper;

    public TaskController(TaskService tasks, CurrentUser currentUser, DtoMapper mapper) {
        this.tasks = tasks;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /**
     * @param scope {@code MINE}, {@code AVAILABLE}, {@code GROUP} or {@code COMPLETED}
     */
    @GetMapping
    public PageResponse<TaskDtos.TaskSummary> inbox(
            @RequestParam(defaultValue = "AVAILABLE") TaskService.InboxScope scope,
            @RequestParam(required = false) WorkflowStep step,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int pageSize = Math.clamp(size, 1, 200);
        TaskService.TaskInbox inbox = tasks.inbox(currentUser.user(), scope, step,
                Math.max(0, page), pageSize);

        return PageResponse.of(inbox.items().stream().map(mapper::toDto).toList(),
                page, pageSize, inbox.total());
    }

    /** Counts per scope, so the navigation can show badges without loading the lists. */
    @GetMapping("/counts")
    public TaskCounts counts() {
        var user = currentUser.user();
        return new TaskCounts(
                tasks.inbox(user, TaskService.InboxScope.MINE, null, 0, 1).total(),
                tasks.inbox(user, TaskService.InboxScope.AVAILABLE, null, 0, 1).total(),
                tasks.inbox(user, TaskService.InboxScope.GROUP, null, 0, 1).total());
    }

    public record TaskCounts(long mine, long available, long group) {
    }

    @GetMapping("/{taskKey}")
    public TaskDtos.TaskSummary get(@PathVariable long taskKey) {
        return mapper.toDto(tasks.require(currentUser.user(), taskKey));
    }

    /** Takes the task so nobody else works on it. */
    @PostMapping("/{taskKey}/assign")
    public TaskDtos.TaskSummary assignToMe(@PathVariable long taskKey) {
        return mapper.toDto(tasks.assignToSelf(currentUser.user(), taskKey));
    }

    /** Puts the task back in the unit's queue. */
    @PostMapping("/{taskKey}/unassign")
    public TaskDtos.TaskSummary release(@PathVariable long taskKey) {
        return mapper.toDto(tasks.release(currentUser.user(), taskKey));
    }

    /** Finishes the task; the engine then routes the complaint to the next step. */
    @PostMapping("/{taskKey}/complete")
    public ClaimDtos.ClaimSummary complete(@PathVariable long taskKey,
                                           @Valid @RequestBody TaskDtos.CompleteTaskRequest request) {
        return mapper.toSummary(tasks.complete(currentUser.user(), taskKey,
                new CompleteTaskCommand(request.decision(), request.comment(), request.resolution(),
                        request.rejectionReason(), request.type(), request.priority())));
    }

    /** The decisions available at a step, so the form can render its buttons. */
    @GetMapping("/decisions")
    public List<TaskDtos.DecisionOption> decisions(@RequestParam WorkflowStep step) {
        return mapper.decisionsFor(step);
    }
}
