import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/stats': 'http://localhost:8080',
      '/workflows': 'http://localhost:8080',
      '/executions': 'http://localhost:8080',
      '/workers': 'http://localhost:8080',
    },
  },
});
