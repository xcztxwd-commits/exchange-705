import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/DesktopTrade.vue', import.meta.url), 'utf8')

for (const kind of ['Login', 'Register']) {
  test(`${kind}: Enter uses the existing submit action and ignores composition/repeat`, () => {
    const id = `pc-${kind.toLowerCase()}-form`
    const form = source.match(new RegExp(`<form id="${id}"[^>]*>`))?.[0]
    assert.ok(form)
    assert.ok(form.includes(`@submit.prevent="on${kind}Submit"`))
    assert.match(form, /novalidate/)
    assert.ok(source.includes(`<button type="submit" form="${id}" :disabled="`))
    assert.ok(!source.includes(`@click="on${kind}Submit"`))
    const guard = new Function('$event', form.match(/@keydown.enter="([^"]+)"/)[1])
    for (const [fields, expected] of [
      [{}, false],
      [{ isComposing: true }, true],
      [{ keyCode: 229 }, true],
      [{ repeat: true }, true],
    ]) {
      let prevented = false
      guard({ ...fields, preventDefault() { prevented = true } })
      assert.equal(prevented, expected)
    }
  })
}

test('CAPTCHA refresh remains a non-submit button inside the registration form', () => {
  const captcha = readFileSync(new URL('../src/components/RegistrationCaptcha.vue', import.meta.url), 'utf8')
  for (const button of captcha.matchAll(/<button\b[^>]*>/g)) {
    assert.match(button[0], /type="button"/)
  }
})
