import axios from "axios";

const api = axios.create({
  baseURL: "http://localhost:8080",
});

export async function fetchTasks(date) {
  const res = await api.get("/api/tasks", { params: { date } });
  return res.data;
}

export async function createTask(date, title) {
  const res = await api.post("/api/tasks", { date, title });
  return res.data;
}

export async function setTaskDone(id, done) {
  const res = await api.patch(`/api/tasks/${id}/done`, null, {
    params: { done },
  });
  return res.data;
}