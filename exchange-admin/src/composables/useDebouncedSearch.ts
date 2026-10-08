import { onBeforeUnmount } from 'vue'

export function useDebouncedSearch(search: () => void, delay = 250) {
  let timer: ReturnType<typeof setTimeout> | undefined
  const cancel = () => { clearTimeout(timer); timer = undefined }
  const schedule = () => { cancel(); timer = setTimeout(() => { timer = undefined; search() }, delay) }
  onBeforeUnmount(cancel)
  return { schedule, cancel }
}
