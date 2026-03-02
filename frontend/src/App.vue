<script setup>
import { ref, onMounted, computed } from "vue";
import { fetchTasks, createTask, setTaskDone } from "./api/tasks";

const today = new Date();
const yyyy = today.getFullYear();
const mm = String(today.getMonth() + 1).padStart(2, "0");
const dd = String(today.getDate()).padStart(2, "0");
const selectedDate = ref(`${yyyy}-${mm}-${dd}`);

const tasks = ref([]);
const newTitle = ref("");

async function load() {
  tasks.value = await fetchTasks(selectedDate.value);
}

async function addTask() {
  const title = newTitle.value.trim();
  if (!title) return;

  await createTask(selectedDate.value, title);
  newTitle.value = "";
  await load();
}

async function toggleDone(task) {
  await setTaskDone(task.id, !task.done);
  task.done = !task.done; // 화면 즉시 반영
}

onMounted(load);

const prettyDate = computed(() => selectedDate.value);
</script>

<template>
  <div class="container">
    <h1>ToDo + Pomodoro</h1>

    <div class="row">
      <label>날짜</label>
      <input type="date" v-model="selectedDate" @change="load" />
      <button @click="load">불러오기</button>
    </div>

    <div class="row">
      <input
        placeholder="할 일을 입력하세요"
        v-model="newTitle"
        @keyup.enter="addTask"
      />
      <button @click="addTask">추가</button>
    </div>

    <ul class="list">
      <li v-for="t in tasks" :key="t.id" class="item">
        <input type="checkbox" :checked="t.done" @change="toggleDone(t)" />
        <span :class="{ done: t.done }">{{ t.title }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.container {
  max-width: 520px;
  margin: 40px auto;
  font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
}
.row {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}
.row input[type="date"] {
  padding: 6px 8px;
}
.row input[placeholder] {
  flex: 1;
  padding: 8px 10px;
}
button {
  padding: 8px 12px;
  cursor: pointer;
}
.list {
  padding-left: 0;
  list-style: none;
}
.item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 4px;
  border-bottom: 1px solid #eee;
}
.done {
  text-decoration: line-through;
  opacity: 0.6;
}
</style>