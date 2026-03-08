package com.shchoi.todolist.controller.dto;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class TaskCreateRequest {
    private String date;
    private String title;
    private Integer priority;
    private String deadline; // nullable, YYYY-MM-DD
}

