// Shared by the desktop entry and its same-origin mobile entry.
(() => {
  const layout = document.currentScript.dataset.layout;
  // Keep the dedicated mobile port usable at any window size.
  if (layout === 'mobile' && !location.pathname.startsWith('/mobile/')) return;
  const narrow = matchMedia('(max-width: 768px)');
  function switchLayout() {
    if (narrow.matches === (layout === 'mobile')) return;
    const target = new URL(location.href);
    const route = new URL(location.hash.slice(1) || '/', location.origin);
    if (narrow.matches) {
      target.pathname = '/mobile/';
      const form = ['login', 'register', 'forgot'].find(key => route.searchParams.get(key) === '1');
      target.hash = form ? `/${form === 'forgot' ? 'forgot-password' : form}`
        : route.pathname === '/language' ? '/language' : '/home';
    } else {
      target.pathname = '/';
      const form = { '/login': 'login', '/register': 'register', '/forgot-password': 'forgot' }[route.pathname];
      target.hash = form ? `/?${form}=1` : route.pathname === '/language' ? '/language' : '/';
    }
    location.replace(target.href);
  }
  narrow.addEventListener('change', switchLayout);
  switchLayout();
})();
