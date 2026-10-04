process.env.NODE_ENV = 'production';
process.env.SEED_DEMO_DATA = process.env.SEED_DEMO_DATA || 'true';
await import('./index.js');
