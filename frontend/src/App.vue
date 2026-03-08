<script setup>
import { ref, onMounted, computed, watch } from "vue";
import { Calendar } from "v-calendar";
import "v-calendar/style.css";

import { fetchTasks, createTask, setTaskDone } from "./api/tasks";
import axios from "axios";

const api = axios.create({ baseURL: "http://localhost:8080" });

function toYmd(dateObj) {
  const yyyy = dateObj.getFullYear();
  const mm = String(dateObj.getMonth() + 1).padStart(2, "0");
  const dd = String(dateObj.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

const todayYmd = toYmd(new Date());
const selectedDate = ref(todayYmd);
const tasks = ref([]);
const allTasks = ref([]);       // 전체 내역
 const incompleteTasks = ref([]); // 누적 미완료
const newTitle = ref("");
const newDeadline = ref("");
const newPriority = ref(2);
const isAdding = ref(false);

// 탭 상태: 'tasks' | 'incomplete' | 'all' | 'stats'
const activeTab = ref("tasks");

// 달력 attributes (데드라인 마스크)
const calendarAttrs = computed(() => {
  const attrs = [{
    key: "selected",
    highlight: { color: "orange", fillMode: "solid" },
    dates: new Date(selectedDate.value + "T00:00:00")
  }];
  // 데드라인이 있는 미완료 태스크 — 보라/남색 계열 하이라이트
  const deadlineDates = allTasks.value
    .filter(t => !t.done && t.deadline)
    .map(t => new Date(t.deadline + "T00:00:00"));
  if (deadlineDates.length) {
    attrs.push({
      key: "deadlines",
      highlight: { color: "purple", fillMode: "light" },
      dates: deadlineDates
    });
  }
  return attrs;
});

const selectedDateObj = computed(() => new Date(selectedDate.value + "T00:00:00"));

const formattedDate = computed(() => {
  const d = new Date(selectedDate.value + "T00:00:00");
  const weekdays = ["일", "월", "화", "수", "목", "금", "토"];
  return {
    month: d.getMonth() + 1,
    day: d.getDate(),
    weekday: weekdays[d.getDay()],
    year: d.getFullYear(),
  };
});

// 완료 애니메이션 중인 태스크 ID 집합
const completingIds = ref(new Set());

// 선택된 날짜의 할일 + 누적 미완료, 우선순위 오름차순 정렬
// completingIds에 있는 항목은 애니메이션이 끝날 때까지 목록에 유지
const displayTasks = computed(() => {
  const dated = tasks.value.filter(t => !t.done || completingIds.value.has(t.id));
  const overflow = incompleteTasks.value.filter(
    t => t.date < selectedDate.value && !dated.find(d => d.id === t.id)
  );
  return [...overflow, ...dated].sort((a, b) => (a.priority || 2) - (b.priority || 2));
});

// 전체 내역 정렬: 미완료 → 완료, 같은 완료 상태면 날짜 오름차순
const sortedAllTasks = computed(() => {
  return [...allTasks.value].sort((a, b) => {
    if (a.done !== b.done) return a.done ? 1 : -1;
    return a.date.localeCompare(b.date);
  });
});

const doneCount = computed(() => displayTasks.value.filter(t => t.done).length);
const totalCount = computed(() => displayTasks.value.length);
const progress = computed(() => totalCount.value === 0 ? 0 : Math.round((doneCount.value / totalCount.value) * 100));

// 선택날짜 통계 (오늘/다른 날짜 공통)
const selectedDateTasks = computed(() => allTasks.value.filter(t => t.date === selectedDate.value));
const selectedDateDone = computed(() => selectedDateTasks.value.filter(t => t.done).length);
const selectedDateTotal = computed(() => selectedDateTasks.value.length);
const selectedDateRate = computed(() => selectedDateTotal.value === 0 ? 0 : Math.round((selectedDateDone.value / selectedDateTotal.value) * 100));
const selectedDateFocusMin = computed(() => (pomoStats.value.byDate || {})[selectedDate.value] || 0);
const selectedDateSessions = computed(() => (pomoStats.value.sessionsByDate || {})[selectedDate.value] || 0);
const isToday = computed(() => selectedDate.value === todayYmd);
const statLabel = computed(() => isToday.value ? '오늘' : `${formattedDate.value.month}/${formattedDate.value.day}`);

async function loadTasks() {
  tasks.value = await fetchTasks(selectedDate.value);
}
async function loadAll() {
  try {
    const [inc, all] = await Promise.all([
      api.get("/api/tasks/incomplete").then(r => r.data),
      api.get("/api/tasks/all").then(r => r.data),
    ]);
    incompleteTasks.value = inc;
    allTasks.value = all;
  } catch(e) {
    console.warn("loadAll 실패:", e);
  }
}

async function addTask() {
  const title = newTitle.value.trim();
  if (!title) return;
  isAdding.value = true;
  await api.post("/api/tasks", {
    date: selectedDate.value,
    title,
    priority: newPriority.value,
    deadline: newDeadline.value || null,
  });
  newTitle.value = "";
  newDeadline.value = "";
  newPriority.value = 2;
  await loadTasks();
  await loadAll();
  isAdding.value = false;
}

async function toggleDone(t) {
  const newDone = !t.done;
  await setTaskDone(t.id, newDone);
  if (newDone) {
    // completingIds에 추가 → displayTasks에서 아직 안 사라짐 → 애니메이션 재생 가능
    completingIds.value = new Set([...completingIds.value, t.id]);
    t.done = true;
    // 애니메이션(0.8s) 끝난 후에 목록 갱신 + completingIds 제거
    setTimeout(async () => {
      completingIds.value.delete(t.id);
      completingIds.value = new Set(completingIds.value);
      await loadTasks();
      await loadAll();
    }, 900);
  } else {
    t.done = false;
    await loadTasks();
    await loadAll();
  }
}

async function setPriority(t, p) {
  await api.patch(`/api/tasks/${t.id}`, { priority: p });
  t.priority = p;
  await loadAll();
}

async function setDeadline(t, d) {
  await api.patch(`/api/tasks/${t.id}`, { deadline: d });
  t.deadline = d || null;
  await loadAll();
}

async function deleteTask(t) {
  if (!confirm(`"${t.title}" 을 삭제할까요?`)) return;
  await api.delete(`/api/tasks/${t.id}`);
  await loadTasks();
  await loadAll();
}

async function onDayClick(day) {
  selectedDate.value = toYmd(day.date);
  activeTab.value = 'tasks'; // 날짜 클릭 시 항상 할 일 탭으로
  await loadTasks();
}

async function goToToday() {
  selectedDate.value = todayYmd;
  await loadTasks();
}

// 끝날 형식
function fmtDate(d) {
  if (!d) return "";
  const dt = new Date(d + "T00:00:00");
  return `${dt.getMonth()+1}/${dt.getDate()}`;
}
function isOverdue(d) {
  if (!d) return false;
  return d < todayYmd;
}

// 우선순위 레이블
const PRIORITY_LABELS = { 1: "#1", 2: "#2", 3: "#3" };
const PRIORITY_COLORS = { 1: "#e85a4f", 2: "#e8a030", 3: "#6bab5f" };

// 데드라인 편집 상태
const editingDeadline = ref(null); // task.id
const deadlineInput = ref("");

function startEditDeadline(t) {
  editingDeadline.value = t.id;
  deadlineInput.value = t.deadline || "";
}
async function confirmDeadline(t) {
  await setDeadline(t, deadlineInput.value);
  editingDeadline.value = null;
}

// 드래그 우선순위 (inline 순환)
function cyclePriority(t) {
  const next = t.priority === 1 ? 2 : t.priority === 2 ? 3 : 1;
  setPriority(t, next);
}

// ── 드래그 앤 드롭 (같은 우선순위 그룹 내에서만) ──
const dragId = ref(null);
const dragOverIdx = ref(null);
const localOrder = ref([]); // task id 배열, 우선순위 정렬 순서 보존

watch(displayTasks, (val) => {
  if (!dragId.value) {
    localOrder.value = val.map(t => t.id);
  }
}, { immediate: true });

const orderedTasks = computed(() => {
  const map = Object.fromEntries(displayTasks.value.map(t => [t.id, t]));
  const ordered = localOrder.value.map(id => map[id]).filter(Boolean);
  // localOrder에 없는 새 태스크 추가
  displayTasks.value.forEach(t => {
    if (!ordered.find(o => o.id === t.id)) ordered.push(t);
  });
  return ordered;
});

function onDragStart(e, t) {
  dragId.value = t.id;
  e.dataTransfer.effectAllowed = 'move';
}
function onDragOver(e, idx) {
  e.preventDefault();
  const src = orderedTasks.value.find(t => t.id === dragId.value);
  const tgt = orderedTasks.value[idx];
  if (!src || !tgt || src.priority !== tgt.priority) {
    e.dataTransfer.dropEffect = 'none';
    return;
  }
  e.dataTransfer.dropEffect = 'move';
  dragOverIdx.value = idx;
}
function onDrop(e, idx) {
  e.preventDefault();
  const src = orderedTasks.value.find(t => t.id === dragId.value);
  const tgt = orderedTasks.value[idx];
  if (!src || !tgt || src.priority !== tgt.priority) {
    dragId.value = null; dragOverIdx.value = null; return;
  }
  const order = [...localOrder.value];
  const fromIdx = order.indexOf(src.id);
  order.splice(fromIdx, 1);
  const toIdx = order.indexOf(tgt.id);
  order.splice(toIdx, 0, src.id);
  localOrder.value = order;
  dragId.value = null; dragOverIdx.value = null;
}
function onDragEnd() {
  dragId.value = null; dragOverIdx.value = null;
}

// ── 뽀모도로 타이머 ──
const pomodoroTask = ref(null);
const pomodoroOpen = ref(false);
const timerOpen = ref(false);
const pomodoroDuration = ref(25);
const breakDuration = ref(5);
const timerPhase = ref("focus");
const timerLeft = ref(0);
const timerRunning = ref(false);
const timerInterval = ref(null);

// 뽀모도로 누적 통계 (localStorage 저장)
const POMO_KEY = "pomotodo_stats";
function loadPomoStats() {
  try { return JSON.parse(localStorage.getItem(POMO_KEY) || "{}"); } catch { return {}; }
}
function savePomoStats(s) {
  localStorage.setItem(POMO_KEY, JSON.stringify(s));
}
const pomoStats = ref(loadPomoStats()); // { totalFocusMin, sessions, byDate: { 'YYYY-MM-DD': min } }
const totalFocusMin = computed(() => pomoStats.value.totalFocusMin || 0);
const totalSessions = computed(() => pomoStats.value.sessions || 0);
const pomoByDate = computed(() => {
  const bd = pomoStats.value.byDate || {};
  return Object.entries(bd).sort((a,b) => b[0].localeCompare(a[0])).slice(0, 7);
});

function recordFocusSession(minutes) {
  const s = loadPomoStats();
  s.totalFocusMin = (s.totalFocusMin || 0) + minutes;
  s.sessions = (s.sessions || 0) + 1;
  s.byDate = s.byDate || {};
  s.byDate[todayYmd] = (s.byDate[todayYmd] || 0) + minutes;
  s.sessionsByDate = s.sessionsByDate || {};
  s.sessionsByDate[todayYmd] = (s.sessionsByDate[todayYmd] || 0) + 1;
  savePomoStats(s);
  pomoStats.value = { ...s };
}

// 포모도로 팝업 내 태스크명 인라인 편집
const editingPomodoroTitle = ref(false);
const pomodoroTitleInput = ref("");

function openPomodoro(t) {
  pomodoroTask.value = t;
  pomodoroOpen.value = true;
  editingPomodoroTitle.value = false;
}
function startEditPomodoroTitle() {
  pomodoroTitleInput.value = pomodoroTask.value?.title || "";
  editingPomodoroTitle.value = true;
  // nextTick 대신 setTimeout으로 포커스
  setTimeout(() => {
    const el = document.getElementById('pomodoro-title-input');
    if (el) el.focus();
  }, 30);
}
async function confirmPomodoroTitle() {
  const newTitle = pomodoroTitleInput.value.trim();
  if (!newTitle || !pomodoroTask.value) { editingPomodoroTitle.value = false; return; }
  if (newTitle !== pomodoroTask.value.title) {
    await api.patch(`/api/tasks/${pomodoroTask.value.id}`, { title: newTitle });
    pomodoroTask.value.title = newTitle;
    await loadTasks();
    await loadAll();
  }
  editingPomodoroTitle.value = false;
}
function startPomodoro() {
  pomodoroOpen.value = false;
  timerPhase.value = "focus";
  timerLeft.value = pomodoroDuration.value * 60;
  timerRunning.value = false;
  timerOpen.value = true;
}
function toggleTimer() {
  if (timerRunning.value) {
    clearInterval(timerInterval.value);
    timerRunning.value = false;
  } else {
    timerRunning.value = true;
    timerInterval.value = setInterval(() => {
      if (timerLeft.value > 0) {
      timerLeft.value--;
      } else {
      clearInterval(timerInterval.value);
      timerRunning.value = false;
      if (timerPhase.value === "focus") {
      // 집중 세션 완료 → 누적 기록
      recordFocusSession(pomodoroDuration.value);
        timerPhase.value = "break";
      timerLeft.value = breakDuration.value * 60;
      } else {
        timerPhase.value = "focus";
          timerLeft.value = pomodoroDuration.value * 60;
        }
      }
    }, 1000);
  }
}
function resetTimer() {
  if (!confirm("처음부터 다시 시작하시겠습니까?\n(지금까지의 집중 시간은 누적됩니다)")) return;
  clearInterval(timerInterval.value);
  timerRunning.value = false;
  timerPhase.value = "focus";
  timerLeft.value = pomodoroDuration.value * 60;
}
function closeTimer() {
  if (!confirm("타이머를 취소하시겠습니까?\n(취소하면 이번 세션 시간은 누적되지 않습니다)")) return;
  clearInterval(timerInterval.value);
  timerRunning.value = false;
  timerOpen.value = false;
}
const timerMM = computed(() => String(Math.floor(timerLeft.value / 60)).padStart(2, "0"));
const timerSS = computed(() => String(timerLeft.value % 60).padStart(2, "0"));
const timerProgress = computed(() => {
  const total = (timerPhase.value === "focus" ? pomodoroDuration.value : breakDuration.value) * 60;
  return ((total - timerLeft.value) / total) * 100;
});

onMounted(async () => {
  await loadTasks();
  await loadAll();
});
</script>

<template>
  <div class="app-shell">
    <div class="bg-orb orb1"></div>
    <div class="bg-orb orb2"></div>
    <div class="bg-grid"></div>

    <!-- 헤더 -->
    <header class="site-header">
      <div class="logo">
        <!-- 뽀모투두 로고: pom + 토마토o + t + 체크o + d + 별o -->
        <div class="logo-wordmark">
          <span class="lw-text">p</span>
          <span class="lw-text">m</span>
          <span class="lw-o lw-o--tomato">
            <svg width="20" height="24" viewBox="0 0 20 24">
              <circle cx="10" cy="15" r="8" fill="#e8784a"/>
              <path d="M7 9 Q10 5 13 9" fill="#6bab5f"/>
              <line x1="10" y1="5.5" x2="10" y2="9" stroke="#6bab5f" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
          </span>
          <span class="lw-text">t</span>
          <span class="lw-o lw-o--check">
            <svg width="20" height="24" viewBox="0 0 20 24">
              <circle cx="10" cy="15" r="8" fill="#6bab5f"/>
              <path d="M6 15 L8.8 18 L14 11" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
            </svg>
          </span>
          <span class="lw-text">d</span>
          <span class="lw-o lw-o--star">
            <svg width="20" height="24" viewBox="0 0 20 24">
              <circle cx="10" cy="15" r="8" fill="#f5c97a"/>
              <text x="10" y="19" text-anchor="middle" font-size="9" fill="white" font-weight="700">★</text>
            </svg>
          </span>
        </div>
      </div>
      <div class="header-tagline">집중하고, 기록하고, 완성하라</div>
    </header>

    <!-- 메인 레이아웃 -->
    <main class="layout">

      <!-- 왼쪽: 달력 + 통계 -->
      <aside class="panel panel-calendar">
        <div class="panel-label">📅 날짜</div>
        <div class="date-hero">
          <div class="date-hero-row">
            <div>
              <span class="date-big">{{ formattedDate.month }}.{{ String(formattedDate.day)}} </span>
              <span class="date-sub">{{ formattedDate.year }}년 · {{ formattedDate.weekday }}요일</span>
            </div>
            <button class="today-btn" :class="{ 'today-btn--active': selectedDate !== todayYmd }"
              :disabled="selectedDate === todayYmd" @click="goToToday">↺ 오늘로</button>
          </div>
        </div>
        <div class="calendar-wrap">
          <Calendar :attributes="calendarAttrs" :is-dark="false" @dayclick="onDayClick" />
        </div>
        <div class="cal-legend">
          <span class="legend-dot legend-dot--deadline"></span><span class="legend-text">데드라인</span>
        </div>

        <!-- 선택 날짜 통계 -->
        <div class="stats-section">
          <div class="panel-label" style="margin-top:24px">📊 {{ statLabel }}의 통계</div>
          <div class="stats-big">
            <div class="stats-ring-wrap">
              <svg class="stats-ring" viewBox="0 0 80 80">
                <circle cx="40" cy="40" r="32" fill="none" stroke="rgba(0,0,0,0.07)" stroke-width="8"/>
                <circle cx="40" cy="40" r="32" fill="none" :stroke="selectedDateRate >= 80 ? '#6bab5f' : '#e8784a'"
                  stroke-width="8" stroke-linecap="round"
                  :stroke-dasharray="201"
                  :stroke-dashoffset="201 - (201 * selectedDateRate / 100)"
                  transform="rotate(-90 40 40)"
                  style="transition: stroke-dashoffset 0.8s ease"/>
              </svg>
              <span class="stats-ring-label">{{ selectedDateRate }}%</span>
            </div>
            <div class="stats-nums">
              <div class="stats-row"><span class="stats-label">전체</span><span class="stats-val">{{ selectedDateTotal }}</span></div>
              <div class="stats-row"><span class="stats-label">완료</span><span class="stats-val" style="color:#6bab5f">{{ selectedDateDone }}</span></div>
              <div class="stats-row"><span class="stats-label">미완료</span><span class="stats-val" style="color:#e85a4f">{{ selectedDateTotal - selectedDateDone }}</span></div>
            </div>
          </div>
          <div class="panel-label" style="margin-top:20px">🍅 {{ statLabel }}의 집중 시간</div>
          <div class="pomo-stat-cards">
            <div class="pomo-stat-card">
              <span class="pomo-stat-num">{{ Math.floor(selectedDateFocusMin / 60) }}h {{ selectedDateFocusMin % 60 }}m</span>
              <span class="pomo-stat-label">집중 시간</span>
            </div>
            <div class="pomo-stat-card">
              <span class="pomo-stat-num">{{ selectedDateSessions }}</span>
              <span class="pomo-stat-label">완료 세션</span>
            </div>
          </div>
        </div>
      </aside>

      <!-- 오른쪽: 탭 패널 -->
      <section class="panel panel-tasks">
        <!-- 탭 -->
        <div class="tabs">
          <button class="tab" :class="{ active: activeTab==='tasks' }" @click="activeTab='tasks'">할 일</button>
          <button class="tab" :class="{ active: activeTab==='all' }" @click="activeTab='all'; loadAll()">전체 내역</button>
        </div>

        <!-- 탭: 할 일 -->
        <div v-if="activeTab === 'tasks'">

          <!-- 입력 영역: 우선순위 + 데드라인 + 입력창 한 묶음 -->
          <div class="add-section">
            <div class="add-unified">
              <div class="add-top-row">
                <div class="add-opt-group">
                  <span class="add-opt-label">우선순위</span>
                  <div class="prio-btns">
                    <button v-for="p in [1,2,3]" :key="p" class="prio-btn"
                      :class="{ 'prio-btn--active': newPriority === p }"
                      :style="newPriority===p ? { background: PRIORITY_COLORS[p], borderColor: PRIORITY_COLORS[p], color:'#fff' } : {}"
                      @click="newPriority = p">{{ PRIORITY_LABELS[p] }}</button>
                  </div>
                </div>
                <div class="add-opt-group">
                  <span class="add-opt-label">데드라인</span>
                  <input type="date" class="deadline-input-mini" v-model="newDeadline" :min="todayYmd" />
                </div>
              </div>
              <div class="add-bottom-row">
                <input class="task-input" placeholder="새로운 할 일을 입력하세요…" v-model="newTitle" @keyup.enter="addTask" />
                <button class="add-btn" @click="addTask" :disabled="isAdding"><span class="add-icon">+</span></button>
              </div>
            </div>
          </div>

          <!-- 할 일 목록 -->
          <ul class="task-list">
            <li v-for="(t, i) in orderedTasks" :key="t.id" class="task-item"
              :class="{
                'task-done': t.done,
                'task-overflow': t.date !== selectedDate,
                'task-dragging': dragId === t.id,
                'task-drag-over': dragOverIdx === i && dragId !== t.id
              }"
              :style="{ animationDelay: i * 0.04 + 's' }"
              draggable="true"
              @dragstart="onDragStart($event, t)"
              @dragover="onDragOver($event, i)"
              @drop="onDrop($event, i)"
              @dragend="onDragEnd">

              <!-- 드래그 핸들 -->
              <span class="drag-handle" title="드래그로 순서 변경">⠿</span>

              <!-- 체크박스 -->
              <label class="task-check">
                <input type="checkbox" :checked="t.done" @change="toggleDone(t)" />
                <span class="checkmark">
                  <svg v-if="t.done" viewBox="0 0 12 10" fill="none"><path d="M1 5L4.5 8.5L11 1.5" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </span>
              </label>

              <!-- 본문 (폼모도로 시작) -->
              <div class="task-body" @click="openPomodoro(t)">
                <span class="task-title">{{ t.title }}</span>
                <div class="task-meta">
                  <span v-if="t.date !== selectedDate" class="overflow-badge" :class="{ 'overflow-badge--overdue': t.date < todayYmd }">📌 {{ fmtDate(t.date) }}</span>
                  <span v-if="t.deadline" class="deadline-badge" :class="{ 'deadline-badge--over': isOverdue(t.deadline) }">
                    🚩 {{ fmtDate(t.deadline) }}{{ isOverdue(t.deadline) ? ' ⚠️' : '' }}
                  </span>
                </div>
              </div>

              <!-- 우선순위 버튼 -->
              <button class="prio-badge" :style="{ background: PRIORITY_COLORS[t.priority||2] }"
                @click.stop="cyclePriority(t)" :title="'우선순위: ' + PRIORITY_LABELS[t.priority||2] + ' (\ud074\ub9ad\ud558\uba74 \ubcc0\uacbd)'">
                {{ PRIORITY_LABELS[t.priority||2] }}
              </button>

              <!-- 데드라인 수정 -->
              <div class="deadline-edit" v-if="editingDeadline === t.id">
                <input type="date" class="deadline-input-mini" v-model="deadlineInput" @keyup.enter="confirmDeadline(t)" @blur="confirmDeadline(t)" />
              </div>
              <button v-else class="icon-btn deadline-icon-btn" @click.stop="startEditDeadline(t)" title="데드라인 수정">🚩</button>

              <!-- 삭제 -->
              <button class="icon-btn icon-btn--del" @click.stop="deleteTask(t)" title="삭제">×</button>
            </li>
          </ul>

          <div v-if="displayTasks.length === 0" class="empty-state">
            <div class="empty-icon">✦</div>
            <p class="empty-text">이 날의 할 일이 없습니다</p>
            <p class="empty-sub">위에서 새로운 할 일을 추가해보세요</p>
          </div>
        </div>

        <!-- 탭: 전체 내역 -->
        <div v-if="activeTab === 'all'">
          <div class="panel-label">📂 전체 태스크 내역</div>
          <ul class="task-list">
            <li v-for="t in sortedAllTasks" :key="t.id" class="task-item task-item--compact task-item--history" :class="{ 'task-done': t.done }">
              <label class="task-check">
                <input type="checkbox" :checked="t.done" @change="toggleDone(t)" />
                <span class="checkmark"><svg v-if="t.done" viewBox="0 0 12 10" fill="none"><path d="M1 5L4.5 8.5L11 1.5" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
              </label>
              <div class="task-body">
                <span class="task-title">{{ t.title }}</span>
                <div class="task-meta">
                  <span class="overflow-badge">{{ t.date }}</span>
                  <span v-if="t.deadline" class="deadline-badge" :class="{ 'deadline-badge--over': isOverdue(t.deadline) }">
                    🚩 {{ fmtDate(t.deadline) }}
                  </span>
                </div>
              </div>
              <button class="prio-badge" :style="{ background: PRIORITY_COLORS[t.priority||2] }" @click.stop="cyclePriority(t)">{{ PRIORITY_LABELS[t.priority||2] }}</button>
              <button class="icon-btn icon-btn--del" @click.stop="deleteTask(t)">×</button>
            </li>
          </ul>
          <div v-if="allTasks.length === 0" class="empty-state">
            <div class="empty-icon">✦</div>
            <p class="empty-text">아직 태스크가 없어요</p>
          </div>
        </div>
      </section>
    </main>

    <!-- 드로어: 폼모도로 세션 선택 -->
    <div v-if="pomodoroOpen" class="overlay" @click.self="pomodoroOpen=false">
      <div class="popup popup-pomodoro">
        <div class="popup-title">🍅 포모도로 시작</div>
        <!-- 태스크명 인라인 편집 -->
        <div class="popup-task-name" v-if="!editingPomodoroTitle" @click="startEditPomodoroTitle" title="클릭하여 수정" style="cursor:pointer">
          {{ pomodoroTask?.title }}
          <span class="popup-task-edit-hint">✎</span>
        </div>
        <div class="popup-task-name popup-task-name--editing" v-else>
          <input
            id="pomodoro-title-input"
            class="popup-title-input"
            v-model="pomodoroTitleInput"
            @keyup.enter="confirmPomodoroTitle"
            @keyup.esc="editingPomodoroTitle = false"
            @blur="confirmPomodoroTitle"
          />
        </div>
        <div class="pomo-settings">
          <div class="pomo-set-row">
            <span>집중 시간</span>
            <div class="pomo-stepper">
              <button @click="pomodoroDuration = Math.max(1, pomodoroDuration-5)">-</button>
              <span>{{ pomodoroDuration }}분</span>
              <button @click="pomodoroDuration = Math.min(90, pomodoroDuration+5)">+</button>
            </div>
          </div>
          <div class="pomo-set-row">
            <span>휴식 시간</span>
            <div class="pomo-stepper">
              <button @click="breakDuration = Math.max(1, breakDuration-1)">-</button>
              <span>{{ breakDuration }}분</span>
              <button @click="breakDuration = Math.min(30, breakDuration+1)">+</button>
            </div>
          </div>
        </div>
        <div class="popup-actions">
          <button class="popup-cancel" @click="pomodoroOpen=false">취소</button>
          <button class="popup-start" @click="startPomodoro"><span class="popup-start-icon">▶</span> 시작하기</button>
        </div>
      </div>
    </div>

    <!-- 드로어: 타이머 -->
    <div v-if="timerOpen" class="overlay" @click.self="closeTimer">
      <div class="popup popup-timer" :class="timerPhase === 'break' ? 'popup-timer--break' : ''">
        <div class="timer-phase-label">{{ timerPhase === 'focus' ? '🔥 집중' : '☕ 휴식' }}</div>
        <div class="timer-task">{{ pomodoroTask?.title }}</div>

        <!-- 원형 프로그레스 -->
        <div class="timer-ring-wrap">
          <svg class="timer-ring" viewBox="0 0 160 160">
            <circle cx="80" cy="80" r="68" fill="none" stroke="rgba(0,0,0,0.07)" stroke-width="10"/>
            <circle cx="80" cy="80" r="68" fill="none"
              :stroke="timerPhase === 'focus' ? '#e8784a' : '#6bab5f'"
              stroke-width="10" stroke-linecap="round"
              :stroke-dasharray="427"
              :stroke-dashoffset="427 * (1 - timerProgress/100)"
              transform="rotate(-90 80 80)"
              style="transition: stroke-dashoffset 1s linear"/>
          </svg>
          <div class="timer-display">{{ timerMM }}:{{ timerSS }}</div>
        </div>

        <div class="timer-btns">
          <button class="timer-btn timer-btn--reset" @click="resetTimer" title="되돌리기">↺</button>
          <button class="timer-btn timer-btn--main" @click="toggleTimer">
            {{ timerRunning ? '⏸ 일시정지' : '▶ 시작' }}
          </button>
          <button class="timer-btn timer-btn--close" @click="closeTimer" title="취소">×</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ── 기본 변수 ── */
:root {
  --bg: #faf7f3;
  --surface: rgba(255,255,255,0.85);
  --surface-hover: rgba(255,255,255,0.95);
  --border: rgba(0,0,0,0.07);
  --accent: #e8784a;
  --accent-glow: rgba(232,120,74,0.2);
  --accent2: #d4900a;
  --text-primary: #1a1612;
  --text-muted: rgba(26,22,18,0.5);
  --text-faint: rgba(26,22,18,0.25);
  --font-display: 'Playfair Display', Georgia, serif;
  --font-body: 'Gowun Dodum', 'Malgun Gothic', sans-serif;
  --font-mono: 'DM Mono', monospace;
}

/* ── 앱 셸 ── */
.app-shell {
  position: relative;
  min-height: 100vh;
  padding: 0 0 60px;
  overflow-x: hidden;
  font-family: var(--font-body);
  color: var(--text-primary);
}

/* ── 배경 장식 ── */
.bg-orb {
  position: fixed;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
  z-index: 0;
}
.orb1 {
  width: 600px; height: 600px;
  background: radial-gradient(circle, rgba(232,120,74,0.1), transparent 70%);
  top: -150px; left: -150px;
  animation: driftA 18s ease-in-out infinite alternate;
}
.orb2 {
  width: 500px; height: 500px;
  background: radial-gradient(circle, rgba(212,144,10,0.08), transparent 70%);
  bottom: 0; right: -100px;
  animation: driftB 22s ease-in-out infinite alternate;
}
@keyframes driftA { from { transform: translate(0,0); } to { transform: translate(60px, 40px); } }
@keyframes driftB { from { transform: translate(0,0); } to { transform: translate(-40px, -60px); } }

.bg-grid {
  position: fixed;
  inset: 0;
  background-image:
    linear-gradient(rgba(0,0,0,0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0,0,0,0.04) 1px, transparent 1px);
  background-size: 48px 48px;
  pointer-events: none;
  z-index: 0;
}

/* ── 헤더 ── */
.site-header {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28px 48px 20px;
  border-bottom: 1px solid rgba(0,0,0,0.08);
}
.logo {
  display: flex;
  align-items: center;
}
/* 로고 워드마크 */
.logo-wordmark {
  display: flex;
  align-items: center;
  gap: 0;
  line-height: 1;
}
.lw-text {
  font-family: 'Gowun Dodum', 'Malgun Gothic', sans-serif;
  font-size: 22px;
  font-weight: 700;
  color: #e8784a;
  letter-spacing: 0.5px;
  line-height: 1;
}
.lw-o {
  display: inline-flex;
  align-items: flex-end;
  margin: 0 0.5px;
  line-height: 1;
}
.lw-o svg {
  display: block;
}
.header-tagline {
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
  font-family: var(--font-mono);
}

/* ── 레이아웃 ── */
.layout {
  position: relative;
  z-index: 10;
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 24px;
  max-width: 1080px;
  margin: 40px auto 0;
  padding: 0 32px;
}

/* ── 패널 공통 ── */
.panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 20px;
  padding: 28px;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: 0 2px 20px rgba(0,0,0,0.06), 0 1px 4px rgba(0,0,0,0.04);
  transition: border-color 0.3s, box-shadow 0.3s;
}
.panel:hover {
  border-color: rgba(232,120,74,0.2);
  box-shadow: 0 4px 28px rgba(0,0,0,0.09), 0 1px 6px rgba(0,0,0,0.05);
}

.panel-label {
  font-size: 11px;
  font-family: var(--font-mono);
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--accent);
  margin-bottom: 20px;
}

/* ── 날짜 히어로 ── */
.date-hero {
  margin-bottom: 24px;
}
.date-hero-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}
.date-big {
  font-family: var(--font-display);
  font-size: 52px;
  font-weight: 700;
  line-height: 1;
  background: linear-gradient(135deg, #1a1612, #7a6a5a);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.date-sub {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 6px;
  font-family: var(--font-mono);
}

.today-btn {
  flex-shrink: 0;
  padding: 6px 14px;
  border-radius: 20px;
  border: 1.5px solid rgba(0,0,0,0.12);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.05em;
  cursor: default;
  margin-bottom: 6px;
  transition: background 0.2s, color 0.2s, border-color 0.2s, transform 0.15s, box-shadow 0.2s;
  opacity: 0.4;
}
.today-btn--active {
  border-color: var(--accent);
  color: var(--accent);
  cursor: pointer;
  opacity: 1;
}
.today-btn--active:hover {
  background: var(--accent);
  color: #fff;
  box-shadow: 0 4px 14px var(--accent-glow);
  transform: translateY(-1px);
}
.today-btn--active:active {
  transform: scale(0.96);
}

/* ── 달력 커스텀 ── */
.calendar-wrap {
  border-radius: 14px;
  overflow: hidden;
}

/* ── 태스크 패널 헤더 ── */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.task-stats { }
.stat-chip {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent2);
  background: rgba(245,200,122,0.1);
  border: 1px solid rgba(245,200,122,0.2);
  border-radius: 20px;
  padding: 4px 12px;
}

/* ── 진행률 바 ── */
.progress-track {
  position: relative;
  height: 4px;
  background: rgba(255,255,255,0.07);
  border-radius: 2px;
  margin-bottom: 24px;
  overflow: visible;
}
.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--accent), var(--accent2));
  border-radius: 2px;
  transition: width 0.6s cubic-bezier(0.34, 1.56, 0.64, 1);
  box-shadow: 0 0 10px var(--accent-glow);
}
.progress-label {
  position: absolute;
  right: 0;
  top: -20px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── 입력 ── */
.input-row {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}
.task-input {
  flex: 1;
  background: rgba(255,255,255,0.9);
  border: 1.5px solid rgba(0,0,0,0.1);
  border-radius: 12px;
  padding: 12px 16px;
  color: var(--text-primary);
  font-family: var(--font-body);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
  box-shadow: inset 0 1px 3px rgba(0,0,0,0.04);
}
.task-input::placeholder {
  color: var(--text-faint);
}
.task-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-glow);
}
.add-btn {
  width: 46px; height: 46px;
  background: #1a1612;
  border: none;
  border-radius: 12px;
  color: #fff;
  font-size: 22px;
  font-weight: 300;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s, box-shadow 0.15s, background 0.2s;
  box-shadow: 0 4px 16px rgba(0,0,0,0.2);
  flex-shrink: 0;
}
.add-btn:hover {
  background: var(--accent);
  transform: translateY(-1px) scale(1.04);
  box-shadow: 0 8px 24px rgba(232,120,74,0.4);
}
.add-btn:active {
  transform: scale(0.96);
}
.add-icon {
  line-height: 1;
  margin-top: -1px;
}

/* ── 할 일 목록 ── */
.task-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.task-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: 12px;
  background: rgba(255,255,255,0.7);
  border: 1px solid rgba(0,0,0,0.05);
  cursor: default;
  animation: slideIn 0.3s ease both;
  transition: background 0.2s, border-color 0.2s, box-shadow 0.2s;
}
.task-item:hover {
  background: rgba(255,255,255,0.95);
  border-color: rgba(232,120,74,0.2);
  box-shadow: 0 2px 10px rgba(232,120,74,0.08);
}
@keyframes slideIn {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes taskComplete {
  0%   { opacity: 1; transform: scale(1) translateX(0); background: rgba(255,255,255,0.92); }
  10%  { transform: scale(1.05) translateX(-5px); background: rgba(107,171,95,0.15); }
  22%  { transform: scale(0.96) translateX(4px); }
  36%  { transform: scale(1.08) translateX(-3px); background: rgba(107,171,95,0.2); }
  52%  { transform: scale(1.02) translateX(0); opacity: 1; }
  68%  { transform: scale(0.98) translateX(30px); opacity: 0.8; }
  85%  { transform: scale(0.93) translateX(70px); opacity: 0.3; }
  100% { transform: scale(0.88) translateX(110px); opacity: 0; max-height: 0; margin-top: 0; margin-bottom: 0; padding-top: 0; padding-bottom: 0; border-width: 0; }
}

/* 완료된 항목 — 통통 튀다가 쒹~ 사라지는 애니메이션 (할 일 탭만) */
.task-done:not(.task-item--history) {
  animation: taskComplete 0.8s cubic-bezier(0.22, 1, 0.36, 1) forwards !important;
  pointer-events: none;
  overflow: hidden;
  will-change: transform, opacity;
}
/* 전체 내역 탭 완료 항목 — 애니메이션 없이 흐릿하게만 */
.task-item--history.task-done {
  opacity: 0.38;
  filter: grayscale(0.5);
  background: rgba(0,0,0,0.02) !important;
  border-color: transparent !important;
  box-shadow: none !important;
}
.task-done .task-title {
  text-decoration: line-through;
  color: var(--text-muted);
}

/* 미완료 항목 — 선명하게 강조 */
.task-item:not(.task-done) {
  background: rgba(255,255,255,0.92) !important;
  border-color: rgba(0,0,0,0.08) !important;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}
.task-item:not(.task-done):hover {
  border-color: rgba(232,120,74,0.3) !important;
  box-shadow: 0 4px 16px rgba(232,120,74,0.1) !important;
}

/* 체크박스 커스텀 */
.task-check {
  position: relative;
  flex-shrink: 0;
  cursor: pointer;
}
.task-check input[type="checkbox"] {
  position: absolute;
  opacity: 0;
  width: 0; height: 0;
}
.checkmark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px; height: 22px;
  border-radius: 6px;
  border: 1.5px solid rgba(0,0,0,0.18);
  background: #fff;
  transition: background 0.2s, border-color 0.2s, box-shadow 0.2s;
}
/* 미완료: 테두리 선명하게 */
.task-item:not(.task-done) .checkmark {
  border-color: rgba(0,0,0,0.28);
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
}
/* 완료: 체크 색으로 채워짐 */
.task-check input:checked + .checkmark {
  background: #6bab5f;
  border-color: #6bab5f;
  box-shadow: 0 0 8px rgba(107,171,95,0.4);
}
.checkmark svg {
  width: 12px; height: 10px;
}

.task-title {
  flex: 1;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-primary);
}
.task-num {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-faint);
  flex-shrink: 0;
}

/* ── 탭 ── */
.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 20px;
  background: rgba(0,0,0,0.04);
  border-radius: 12px;
  padding: 4px;
}
.tab {
  flex: 1;
  padding: 8px 10px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-body);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.tab.active {
  background: #fff;
  color: var(--text-primary);
  box-shadow: 0 1px 4px rgba(0,0,0,0.1);
  font-weight: 600;
}
.tab-badge {
  background: #e85a4f;
  color: #fff;
  border-radius: 10px;
  font-size: 10px;
  padding: 1px 6px;
  font-family: var(--font-mono);
}

/* ── 추가 옵션 영역 ── */
.add-section { margin-bottom: 16px; }
.add-options {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.add-opt-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.add-opt-label {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  letter-spacing: 0.05em;
  white-space: nowrap;
}
.prio-btns { display: flex; gap: 4px; }
.prio-btn {
  padding: 4px 10px;
  border-radius: 20px;
  border: 1.5px solid rgba(0,0,0,0.12);
  background: transparent;
  color: var(--text-muted);
  font-size: 11px;
  font-family: var(--font-mono);
  cursor: pointer;
  transition: all 0.15s;
}
.prio-btn:hover { border-color: var(--accent); color: var(--accent); }
.deadline-input-mini {
  padding: 4px 8px;
  border: 1.5px solid rgba(0,0,0,0.1);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-primary);
  font-family: var(--font-mono);
  background: #fff;
  outline: none;
}
.deadline-input-mini:focus { border-color: var(--accent); }

/* ── 태스크 메타 ── */
.task-body {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.task-body:hover .task-title { color: var(--accent); }
.task-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 3px;
}
.overflow-badge {
  font-size: 10px;
  font-family: var(--font-mono);
  background: rgba(255,192,203,0.18);
  color: #e8a0b0;
  border-radius: 6px;
  padding: 1px 7px;
  font-weight: 500;
  letter-spacing: 0.01em;
}
.overflow-badge--overdue {
  background: rgba(210,40,30,0.13);
  color: #c02a1e;
  font-weight: 700;
}
.deadline-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 10px;
  font-family: var(--font-mono);
  font-weight: 600;
  background: rgba(109,40,217,0.1);
  color: #6d28d9;
  border-radius: 6px;
  padding: 1px 8px;
  letter-spacing: 0.02em;
}
.deadline-badge--over {
  background: rgba(232,90,79,0.13);
  color: #c0392b;
}
.task-overflow:not(.task-done) {
  background: rgba(255,220,220,0.08) !important;
}

/* ── 드래그 핸들 & 상태 ── */
.drag-handle {
  flex-shrink: 0;
  font-size: 15px;
  color: var(--text-faint);
  cursor: grab;
  line-height: 1;
  padding: 2px 1px;
  user-select: none;
  transition: color 0.15s;
  letter-spacing: -1px;
}
.drag-handle:active { cursor: grabbing; }
.task-item:not(.task-done):hover .drag-handle { color: rgba(26,22,18,0.3); }
.task-dragging {
  opacity: 0.4 !important;
  box-shadow: 0 10px 30px rgba(0,0,0,0.18) !important;
  transform: scale(1.02);
  z-index: 10;
}
.task-drag-over {
  border-top: 2px solid var(--accent) !important;
}
.task-item--compact { padding: 10px 14px; }

/* ── 우선순위 배지 ── */
.prio-badge {
  flex-shrink: 0;
  font-size: 10px;
  font-family: var(--font-mono);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 3px 8px;
  cursor: pointer;
  transition: transform 0.1s, opacity 0.15s;
  opacity: 0.85;
}
.prio-badge:hover { opacity: 1; transform: scale(1.05); }

/* ── 아이콘 버튼 ── */
.icon-btn {
  flex-shrink: 0;
  background: none;
  border: none;
  font-size: 14px;
  color: var(--text-faint);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: color 0.15s, background 0.15s;
}
.icon-btn:hover { background: rgba(0,0,0,0.05); color: var(--text-muted); }
.icon-btn--del:hover { color: #e85a4f; background: rgba(232,90,79,0.08); }
.deadline-edit { display: flex; align-items: center; }

/* ── 달력 레전드 ── */
.cal-legend {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
}
.legend-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.legend-dot--deadline { background: #8b5cf6; }
.legend-text { font-size: 11px; color: var(--text-muted); font-family: var(--font-mono); }

/* ── 통계 섹션 ── */
.stats-section { }
.stats-big {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 16px;
}
.stats-ring-wrap {
  position: relative;
  width: 80px; height: 80px;
  flex-shrink: 0;
}
.stats-ring { width: 80px; height: 80px; }
.stats-ring-label {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.stats-nums { display: flex; flex-direction: column; gap: 6px; }
.stats-row { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.stats-label { font-size: 11px; color: var(--text-muted); font-family: var(--font-mono); }
.stats-val { font-size: 13px; font-weight: 600; font-family: var(--font-mono); color: var(--text-primary); }

.stats-dates { display: flex; flex-direction: column; gap: 6px; }
.stats-date-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stats-date-label {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--text-muted);
  width: 32px;
  flex-shrink: 0;
}
.stats-date-bar-wrap {
  flex: 1;
  height: 6px;
  background: rgba(0,0,0,0.06);
  border-radius: 3px;
  overflow: hidden;
}
.stats-date-bar {
  height: 100%;
  border-radius: 3px;
  transition: width 0.5s ease;
  min-width: 4px;
}
.stats-date-pct {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--text-muted);
  width: 28px;
  text-align: right;
}

/* ── 뽀모도로 통계 카드 ── */
.pomo-stat-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 12px;
}
.pomo-stat-card {
  background: rgba(232,120,74,0.07);
  border: 1px solid rgba(232,120,74,0.15);
  border-radius: 12px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pomo-stat-num {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 700;
  color: var(--accent);
}
.pomo-stat-label {
  font-size: 10px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
/* add-unified: 우선순위+데드라인+입력창 한 묶음 카드 */
.add-unified {
  background: rgba(255,255,255,0.9);
  border: 1.5px solid rgba(232,120,74,0.18);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(232,120,74,0.07);
}
.add-top-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 14px 8px;
  border-bottom: 1px solid rgba(232,120,74,0.1);
  background: rgba(232,120,74,0.04);
}
.add-bottom-row {
  display: flex;
  gap: 0;
  padding: 0;
}
.add-bottom-row .task-input {
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  padding: 13px 16px;
  flex: 1;
}
.add-bottom-row .task-input:focus {
  box-shadow: none;
  border-color: transparent;
  background: rgba(232,120,74,0.02);
}
.add-bottom-row .add-btn {
  border-radius: 0 14px 14px 0;
  height: 100%;
  min-height: 46px;
  width: 48px;
  box-shadow: none;
}

/* ── 오버레이 ── */
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.35);
  backdrop-filter: blur(4px);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── 팝업 공통 ── */
.popup {
  background: #fff;
  border-radius: 24px;
  padding: 32px;
  box-shadow: 0 24px 80px rgba(0,0,0,0.15);
  width: 360px;
  max-width: 90vw;
  animation: popIn 0.25s cubic-bezier(0.34,1.56,0.64,1) both;
}
@keyframes popIn {
  from { opacity: 0; transform: scale(0.88); }
  to   { opacity: 1; transform: scale(1); }
}
.popup-title {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  color: var(--accent);
  margin-bottom: 8px;
}
.popup-task-name {
  font-size: 14px;
  color: var(--text-muted);
  margin-bottom: 24px;
  font-family: var(--font-body);
  padding: 10px 14px;
  background: rgba(232,120,74,0.07);
  border-radius: 10px;
  border-left: 3px solid var(--accent);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  transition: background 0.15s;
}
.popup-task-name:hover {
  background: rgba(232,120,74,0.12);
}
.popup-task-edit-hint {
  font-size: 12px;
  color: var(--accent);
  opacity: 0.5;
  flex-shrink: 0;
  transition: opacity 0.15s;
}
.popup-task-name:hover .popup-task-edit-hint { opacity: 1; }
.popup-task-name--editing {
  padding: 4px 8px;
  cursor: default;
}
.popup-task-name--editing:hover { background: rgba(232,120,74,0.07); }
.popup-title-input {
  flex: 1;
  width: 100%;
  border: none;
  background: transparent;
  font-size: 14px;
  font-family: var(--font-body);
  color: var(--text-primary);
  outline: none;
  padding: 6px 6px;
  border-radius: 6px;
  border-bottom: 1.5px solid var(--accent);
}

/* ── 폼모도로 세팅 ── */
.pomo-settings { display: flex; flex-direction: column; gap: 16px; margin-bottom: 24px; }
.pomo-set-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  color: var(--text-primary);
  font-family: var(--font-body);
}
.pomo-stepper {
  display: flex;
  align-items: center;
  gap: 0;
  border: 1.5px solid rgba(0,0,0,0.12);
  border-radius: 10px;
  overflow: hidden;
}
.pomo-stepper button {
  width: 36px; height: 36px;
  border: none;
  background: transparent;
  font-size: 18px;
  cursor: pointer;
  color: var(--text-primary);
  transition: background 0.15s;
}
.pomo-stepper button:hover { background: rgba(0,0,0,0.05); }
.pomo-stepper span {
  padding: 0 14px;
  font-family: var(--font-mono);
  font-size: 14px;
  border-left: 1px solid rgba(0,0,0,0.07);
  border-right: 1px solid rgba(0,0,0,0.07);
  min-width: 60px;
  text-align: center;
}
.popup-actions { display: flex; gap: 10px; }
.popup-cancel {
  flex: 1;
  padding: 12px;
  border-radius: 12px;
  border: 1.5px solid rgba(0,0,0,0.1);
  background: transparent;
  font-family: var(--font-body);
  font-size: 14px;
  cursor: pointer;
  color: var(--text-muted);
  transition: background 0.15s;
}
.popup-cancel:hover { background: rgba(0,0,0,0.04); }
.popup-start {
  flex: 2;
  padding: 12px 20px;
  border-radius: 12px;
  border: none;
  background: linear-gradient(135deg, #e8784a, #d4623b);
  color: white !important;
  -webkit-text-fill-color: white !important;
  font-family: var(--font-body);
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  box-shadow: 0 4px 16px rgba(232,120,74,0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  letter-spacing: 0.02em;
  text-shadow: none;
  filter: none;
}
.popup-start:hover { transform: translateY(-1px); box-shadow: 0 8px 24px rgba(232,120,74,0.4); }
.popup-start * {
  color: white !important;
  -webkit-text-fill-color: white !important;
}
.popup-start-icon {
  font-size: 13px;
}

/* ── 타이머 팝업 ── */
.popup-timer {
  text-align: center;
  padding: 36px 32px;
  transition: background 0.5s;
}
.popup-timer--break { background: #f0faf2; }
.timer-phase-label {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--accent);
  margin-bottom: 4px;
  font-family: var(--font-mono);
}
.popup-timer--break .timer-phase-label { color: #6bab5f; }
.timer-task {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 28px;
  max-width: 260px;
  margin-left: auto;
  margin-right: auto;
}
.timer-ring-wrap {
  position: relative;
  width: 160px; height: 160px;
  margin: 0 auto 28px;
}
.timer-ring { width: 160px; height: 160px; }
.timer-display {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 36px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: 0.05em;
}
.timer-btns {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.timer-btn {
  border: none;
  border-radius: 14px;
  cursor: pointer;
  font-family: var(--font-body);
  transition: transform 0.15s, box-shadow 0.15s;
  color: var(--text-primary);
}
.timer-btn--main {
  padding: 12px 28px;
  background: linear-gradient(135deg, #e8784a, #d4623b);
  color: white !important;
  -webkit-text-fill-color: white !important;
  font-size: 15px;
  font-weight: 700;
  box-shadow: 0 4px 16px rgba(232,120,74,0.35);
  letter-spacing: 0.02em;
  text-shadow: none;
  filter: none;
}
.timer-btn--main:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(232,120,74,0.5); }
.popup-timer--break .timer-btn--main { background: linear-gradient(135deg, #6bab5f, #4e8b44); color: white !important; -webkit-text-fill-color: white !important; box-shadow: 0 4px 16px rgba(107,171,95,0.35); }
.timer-btn--reset, .timer-btn--close {
  width: 44px; height: 44px;
  background: #f0ede8;
  color: #1a1612 !important;
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
}
.timer-btn--reset:hover { background: #e8e0d5; }
.timer-btn--close:hover { background: rgba(232,90,79,0.12); color: #e85a4f !important; }

/* ── 빈 상태 ── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 56px 0;
  gap: 10px;
}
.empty-icon {
  font-size: 32px;
  color: var(--text-faint);
  animation: pulse 3s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.2; transform: scale(1); }
  50%       { opacity: 0.5; transform: scale(1.1); }
}
.empty-text {
  font-size: 15px;
  color: var(--text-muted);
  font-family: var(--font-body);
}
.empty-sub {
  font-size: 12px;
  color: var(--text-faint);
  font-family: var(--font-mono);
}

/* ── 반응형 ── */
@media (max-width: 860px) {
  .layout {
    grid-template-columns: 1fr;
    padding: 0 20px;
  }
  .site-header {
    padding: 20px 24px;
  }
  .header-tagline {
    display: none;
  }
}
</style>