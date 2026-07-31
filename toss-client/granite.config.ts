import { defineConfig } from '@apps-in-toss/web-framework/config';

export default defineConfig({
  appName: 'mineportal',
  brand: {
    displayName: '마인포탈',
    primaryColor: '#9b5de5',
    icon: 'https://mineportal.kr/logo.png',
  },
  web: {
    host: 'localhost',
    port: 5173,
    commands: {
      dev: 'vite',
      build: 'vite build',
    },
  },
  permissions: [],
  outdir: 'dist',
});
