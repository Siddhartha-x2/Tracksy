// Apply the preferred appearance before the application and styles render.
(() => {
  let saved;
  try { saved = localStorage.getItem('tracksy-theme'); } catch { /* Storage can be disabled. */ }
  const theme = saved === 'dark' || saved === 'light'
    ? saved
    : 'light';
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#16141a' : '#f7f5f0');
})();
