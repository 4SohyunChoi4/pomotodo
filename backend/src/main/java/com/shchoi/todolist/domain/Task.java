package com.shchoi.todolist.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;

    private String title;

    private boolean done;

    private Integer priority; // 1=높음, 2=보통, 3=낮음

    private LocalDate deadline; // 데드라인 날짜
}