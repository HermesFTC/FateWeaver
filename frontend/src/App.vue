<script setup lang="ts">
import { ref, onMounted } from 'vue';

interface LogFile {
  name: string;
  size: number;
  lastModified: number;
}

interface LiveStatus {
  enabled: boolean;
  running: boolean;
  port: number;
  clients: number;
}

const logs = ref<LogFile[]>([]);
const error = ref<string | null>(null);
const live = ref<LiveStatus | null>(null);
const livePending = ref(false);

const fetchLogs = async () => {
  try {
    const res = await fetch('/fate/logs/list');
    if (!res.ok) throw new Error('Failed to fetch logs');
    logs.value = await res.json();
  } catch (err: any) {
    error.value = err.message;
  }
};

const fetchLive = async () => {
  try {
    const res = await fetch('/fate/live/status');
    if (!res.ok) throw new Error('Failed to fetch live status');
    live.value = await res.json();
  } catch {
    live.value = null;
  }
};

const toggleLive = async () => {
  if (live.value === null || livePending.value) return;
  livePending.value = true;
  try {
    const on = !live.value.enabled;
    const res = await fetch(`/fate/live/enable?on=${on}`);
    if (!res.ok) throw new Error('Failed to change live streaming');
    await fetchLive();
  } catch (err: any) {
    error.value = err.message;
  } finally {
    livePending.value = false;
  }
};

onMounted(() => {
  fetchLogs();
  fetchLive();
  // Client count changes as AdvantageScope connects and disconnects
  setInterval(fetchLive, 2000);
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

      <section v-if="live" class="live-panel" :class="{ on: live.running }">
        <div class="live-info">
          <div class="live-heading">
            <span class="live-dot" :class="{ on: live.running }"></span>
            <h2>Live streaming</h2>
            <span class="live-state">{{ live.running ? 'On' : 'Off' }}</span>
          </div>
          <p v-if="live.running" class="live-detail">
            Port {{ live.port }} &middot;
            {{ live.clients }} {{ live.clients === 1 ? 'client' : 'clients' }} connected.
            Set AdvantageScope's live mode to FateWeaver to watch this robot.
          </p>
          <p v-else class="live-detail">
            Nothing is listening on port {{ live.port }}. Logs are still being recorded to files.
          </p>
          <p class="live-warning">
            The FTC 2026-2027 competition manual prohibits live streaming from third-party
            libraries during competition. Turn this off before matches &mdash; the setting is
            remembered across robot restarts. At an event, the
            &ldquo;Enable/Disable FateWeaver Live&rdquo; OpMode does the same thing from the
            Driver Station, without needing this page.
          </p>
        </div>
        <button class="btn live-toggle" :disabled="livePending" @click="toggleLive">
          {{ live.enabled ? 'Turn off' : 'Turn on' }}
        </button>
      </section>

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

