package com.shchoi.todolist.controller;

import com.shchoi.todolist.domain.Task;
import com.shchoi.todolist.repo.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@CrossOrigin // Vue에서 접근 가능하게
public class TaskController {

    private final TaskRepository taskRepository;

    // 1️⃣ 날짜별 조회
    @GetMapping
    public List<Task> getTasks(@RequestParam String date) {
        return taskRepository.findByDate(LocalDate.parse(date));
    }

    // 2️⃣ 할 일 추가
    @PostMapping
    public Task createTask(@RequestBody com.shchoi.todolist.controller.dto.TaskCreateRequest req) {
        Task task = Task.builder()
                .date(LocalDate.parse(req.getDate()))
                .title(req.getTitle())
                .done(false)
                .build();

        return taskRepository.save(task);
    }

    // 3️⃣ 완료 토글
    @PatchMapping("/{id}/done")
    public Task updateDone(
            @PathVariable Long id,
            @RequestParam boolean done) {

        Task task = taskRepository.findById(id)
                .orElseThrow();

        task.setDone(done);

        return taskRepository.save(task);
    }
}