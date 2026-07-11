<script setup lang="ts">
import { ref, onMounted } from 'vue';

interface LogFile {
  name: string;
  size: number;
  lastModified: number;
}

const logs = ref<LogFile[]>([]);
const error = ref<string | null>(null);

const fetchLogs = async () => {
  try {
    const res = await fetch('/fate/logs/list');
    if (!res.ok) throw new Error('Failed to fetch logs');
    logs.value = await res.json();
  } catch (err: any) {
    error.value = err.message;
  }
};

onMounted(() => {
  fetchLogs();
});

const formatBytes = (bytes: number) => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

const formatDate = (ts: number) => {
  return new Date(ts).toLocaleString();
};
</script>

<template>
  <div class="dashboard">
    <header>
      <h1>FateWeaver Log Dashboard</h1>
    </header>

    <main>
      <div v-if="error" class="error">{{ error }}</div>
      
      <div class="table-container">
        <table>
          <thead>
            <tr>
              <th>File Name</th>
              <th>Size</th>
              <th>Last Modified</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.name">
              <td>{{ log.name }}</td>
              <td>{{ formatBytes(log.size) }}</td>
              <td>{{ formatDate(log.lastModified) }}</td>
              <td>
                <a :href="`/fate/logs/download?file=${encodeURIComponent(log.name)}`" class="btn" download>Download</a>
              </td>
            </tr>
            <tr v-if="logs.length === 0 && !error">
              <td colspan="4" class="empty-state">No logs found.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

