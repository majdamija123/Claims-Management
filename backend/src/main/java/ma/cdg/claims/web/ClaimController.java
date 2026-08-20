package ma.cdg.claims.web;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.security.CurrentUser;
import ma.cdg.claims.service.ClaimSearchCriteria;
import ma.cdg.claims.service.ClaimService;
import ma.cdg.claims.service.CreateClaimCommand;
import ma.cdg.claims.service.TaskService;
import ma.cdg.claims.web.dto.ClaimDtos;
import ma.cdg.claims.web.dto.PageResponse;
import ma.cdg.claims.web.dto.TaskDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Registering, listing and following a complaint. */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ClaimService claims;
    private final TaskService tasks;
    private final CurrentUser currentUser;
    private final DtoMapper mapper;

    public ClaimController(ClaimService claims, TaskService tasks,
                           CurrentUser currentUser, DtoMapper mapper) {
        this.claims = claims;
        this.tasks = tasks;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<ClaimDtos.ClaimDetail> create(
            @Valid @RequestBody ClaimDtos.CreateClaimRequest request) {

        Claim claim = claims.create(new CreateClaimCommand(
                request.customerName(), request.customerEmail(), request.customerPhone(),
                request.customerReference(), request.channel(), request.entity(),
                request.subject(), request.description(), request.type(), request.priority()),
                currentUser.user());

        return ResponseEntity.status(HttpStatus.CREATED).body(detailOf(claim));
    }

    @GetMapping
    public PageResponse<ClaimDtos.ClaimSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<ClaimStatus> status,
            @RequestParam(required = false) List<ClaimType> type,
            @RequestParam(required = false) List<ClaimPriority> priority,
            @RequestParam(required = false) List<ClaimChannel> channel,
            @RequestParam(required = false) WorkflowStep step,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Boolean openOnly,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {

        Page<Claim> result = claims.search(
                criteria(search, status, type, priority, channel, step, assignee,
                        overdue, openOnly, createdFrom, createdTo),
                pageable(page, size, sort, direction));

        return PageResponse.of(result, mapper::toSummary);
    }

    @GetMapping("/{id}")
    public ClaimDtos.ClaimDetail get(@PathVariable Long id) {
        return detailOf(claims.require(id));
    }

    @GetMapping("/by-reference/{reference}")
    public ClaimDtos.ClaimDetail getByReference(@PathVariable String reference) {
        return detailOf(claims.requireByReference(reference));
    }

    @GetMapping("/{id}/history")
    public List<ClaimDtos.ClaimEventDto> history(@PathVariable Long id) {
        return claims.history(claims.require(id).getId()).stream().map(mapper::toDto).toList();
    }

    @PostMapping("/{id}/comments")
    public ClaimDtos.ClaimDetail comment(@PathVariable Long id,
                                         @Valid @RequestBody ClaimDtos.CommentRequest request) {
        claims.addComment(id, request.comment(), currentUser.user());
        return detailOf(claims.require(id));
    }

    @PostMapping("/{id}/cancel")
    public ClaimDtos.ClaimDetail cancel(@PathVariable Long id,
                                        @Valid @RequestBody ClaimDtos.CancelRequest request) {
        return detailOf(claims.cancel(id, request.reason(), currentUser.user()));
    }

    /** Asks the classification model what this complaint looks like, before saving it. */
    @PostMapping("/suggest-type")
    public ClaimDtos.TypeSuggestion suggestType(@Valid @RequestBody ClaimDtos.SuggestTypeRequest request) {
        return mapper.toDto(claims.suggestType(request.subject(), request.description()));
    }

    // ------------------------------------------------------------------ helpers

    private ClaimDtos.ClaimDetail detailOf(Claim claim) {
        List<TaskDtos.TaskSummary> openTasks = List.of();
        if (claim.getProcessInstanceKey() != null && !claim.getStatus().isTerminal()) {
            openTasks = tasks.forProcessInstance(currentUser.user(), claim.getProcessInstanceKey())
                    .stream()
                    .map(mapper::toDto)
                    .toList();
        }
        return mapper.toDetail(claim, claims.history(claim.getId()), openTasks,
                claims.processVariables(claim));
    }

    private ClaimSearchCriteria criteria(String search, List<ClaimStatus> status, List<ClaimType> type,
                                         List<ClaimPriority> priority, List<ClaimChannel> channel,
                                         WorkflowStep step, String assignee, Boolean overdue,
                                         Boolean openOnly, Instant createdFrom, Instant createdTo) {
        return new ClaimSearchCriteria(search,
                status == null ? List.of() : status,
                type == null ? List.of() : type,
                priority == null ? List.of() : priority,
                channel == null ? List.of() : channel,
                step, assignee, overdue, openOnly, createdFrom, createdTo);
    }

    private PageRequest pageable(int page, int size, String sort, String direction) {
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        String property = switch (sort) {
            case "reference", "customerName", "subject", "status", "priority", "type",
                 "slaDueAt", "updatedAt", "closedAt" -> sort;
            default -> "createdAt";
        };
        return PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(sortDirection, property));
    }
}
