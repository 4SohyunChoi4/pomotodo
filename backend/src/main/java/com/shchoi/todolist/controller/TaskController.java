package com.shchoi.todolist.controller;

import com.shchoi.todolist.controller.dto.TaskCreateRequest;
import com.shchoi.todolist.domain.Task;
import com.shchoi.todolist.repo.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@CrossOrigin
public class TaskController {

    private final TaskRepository taskRepository;

    // 1. 날짜별 조회
    @GetMapping
    public List<Task> getTasks(@RequestParam String date) {
        return taskRepository.findByDate(LocalDate.parse(date));
    }

    // 2. 미완료 전체 조회 (누적된 할 일)
    @GetMapping("/incomplete")
    public List<Task> getIncompleteTasks() {
        return taskRepository.findByDoneFalseOrderByDeadlineAscDateAsc();
    }

    // 3. 전체 조회 (통계용)
    @GetMapping("/all")
    public List<Task> getAllTasks() {
        return taskRepository.findAllByOrderByDateDesc();
    }

    // 4. 할 일 추가
    @PostMapping
    public Task createTask(@RequestBody TaskCreateRequest req) {
        Task task = Task.builder()
                .date(LocalDate.parse(req.getDate()))
                .title(req.getTitle())
                .done(false)
                .priority(req.getPriority() != null ? req.getPriority() : 2)
                .deadline(req.getDeadline() != null ? LocalDate.parse(req.getDeadline()) : null)
                .build();
        return taskRepository.save(task);
    }

    // 5. 완료 토글
    @PatchMapping("/{id}/done")
    public Task updateDone(@PathVariable Long id, @RequestParam boolean done) {
        Task task = taskRepository.findById(id).orElseThrow();
        task.setDone(done);
        return taskRepository.save(task);
    }

    // 6. 우선순위 / 데드라인 수정 (TaskCreateRequest 재사용)
    @PatchMapping("/{id}")
    public Task updateTask(@PathVariable Long id, @RequestBody TaskCreateRequest req) {
        Task task = taskRepository.findById(id).orElseThrow();
        if (req.getPriority() != null) task.setPriority(req.getPriority());
        if (req.getDeadline() != null) task.setDeadline(req.getDeadline().isBlank() ? null : LocalDate.parse(req.getDeadline()));
        return taskRepository.save(task);
    }

    // 7. 삭제
    @DeleteMapping("/{id}")
    public void deleteTask(@PathVariable Long id) {
        taskRepository.deleteById(id);
    }
}