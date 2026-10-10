// Shared by the desktop entry and its same-origin mobile entry.
(() => {
  const layout = document.currentScript.dataset.layout;
  // Keep the dedicated mobile port usable at any window size.
  if (layout === 'mobile' && !location.pathname.startsWith('/mobile/')) return;
  const narrow = matchMedia('(max-width: 768px)');
  // Phone fullscreen and rotation change viewport width, not the device layout.
  const phone = matchMedia('(pointer: coarse)').matches && Math.min(screen.width, screen.height) <= 768;
  function switchLayout() {
    const mobile = phone || narrow.matches;
    if (mobile === (layout === 'mobile')) return;
    const target = new URL(location.href);
    const route = layout === 'mobile' ? location.pathname.slice('/mobile'.length) || '/' : location.pathname;
    if (mobile) {
      const form = ['login', 'register', 'forgot'].find(key => target.searchParams.get(key) === '1');
      target.pathname = form ? `/mobile/${form === 'forgot' ? 'forgot-password' : form}`
        : route === '/language' ? '/mobile/language' : '/mobile/home';
      if (form) target.searchParams.delete(form);
    } else {
      const form = { '/login': 'login', '/register': 'register', '/forgot-password': 'forgot' }[route];
      target.pathname = route === '/language' ? '/language' : '/';
      if (form) target.searchParams.set(form, '1');
    }
    target.hash = '';
    location.replace(target.href);
  }
  narrow.addEventListener('change', switchLayout);
  switchLayout();
})();
