package com.shchoi.todolist.repo;
import com.shchoi.todolist.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByDate(LocalDate date);

    List<Task> findByDoneFalseOrderByDeadlineAscDateAsc();

    List<Task> findAllByOrderByDateDesc();
}