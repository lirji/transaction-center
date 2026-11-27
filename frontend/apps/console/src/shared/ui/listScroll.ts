export function rememberListScroll(key: string) {
  try { sessionStorage.setItem(key, String(window.scrollY)) } catch { /* 无会话存储时继续正常导航 */ }
}

export function restoreListScroll(key: string) {
  try {
    const saved = sessionStorage.getItem(key)
    if (saved !== null) {
      const scrollY = Number(saved)
      if (Number.isFinite(scrollY) && scrollY >= 0) window.scrollTo({ top: scrollY })
    }
  } catch { /* 无会话存储时由浏览器正常处理 */ }
}

export function focusWorkSection(id: string) {
  const section = document.getElementById(id)
  section?.focus({ preventScroll: true })
  section?.scrollIntoView({ block: 'start' })
}
