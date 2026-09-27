<script setup lang="ts">
defineProps<{ src: string; dark?: boolean }>()

function replay(event: Event) {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const logo = event.currentTarget as HTMLElement
  logo.classList.remove('logo-glint--replay')
  void logo.offsetWidth
  logo.classList.add('logo-glint--replay')
}

function finish(event: AnimationEvent) {
  if (event.pseudoElement === '::after') {
    (event.currentTarget as HTMLElement).classList.remove('logo-glint--replay')
  }
}
</script>

<template>
  <button
    type="button"
    class="logo-glint"
    :class="{ 'logo-glint--dark': dark }"
    :style="{ '--logo-mask': `url(${src})` }"
    aria-label="Replay FOREX EXCHANGE logo animation"
    @mouseenter="replay"
    @click="replay"
    @animationend="finish"
  >
    <img :src="src" width="515" height="168" alt="" draggable="false" />
  </button>
</template>

<style scoped>
.logo-glint {
  --glint-halo: #b79ae9;
  --glint-flash: #fff;
  position: relative;
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  line-height: 0;
}
.logo-glint--dark { --glint-halo: #9982ee; --glint-flash: #f3eaff; }
.logo-glint:focus-visible { outline: 2px solid #8e66d1; outline-offset: 3px; }
.logo-glint img { display: block; width: 100%; height: auto; }
.logo-glint::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0;
  -webkit-mask: var(--logo-mask) center / contain no-repeat;
  mask: var(--logo-mask) center / contain no-repeat;
  background: linear-gradient(108deg, transparent 46%, var(--glint-halo) 48%, var(--glint-flash) 50%, var(--glint-halo) 52%, transparent 54%);
  background-size: 250% 100%;
  animation: logo-glint-idle 8s linear infinite;
}
.logo-glint--replay::after { animation: logo-glint-replay .75s linear 1; }
@keyframes logo-glint-idle {
  0% { opacity: 0; background-position: 100% 0; }
  1% { opacity: 1; }
  10% { opacity: 1; background-position: 0% 0; }
  11%, 100% { opacity: 0; background-position: 0% 0; }
}
@keyframes logo-glint-replay {
  0% { opacity: 0; background-position: 100% 0; }
  10% { opacity: 1; }
  90% { opacity: 1; background-position: 0% 0; }
  100% { opacity: 0; background-position: 0% 0; }
}
@media (prefers-reduced-motion: reduce) {
  .logo-glint::after, .logo-glint--replay::after { animation: none; opacity: 0; }
}
</style>
